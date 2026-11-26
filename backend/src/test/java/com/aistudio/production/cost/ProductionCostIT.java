package com.aistudio.production.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.budget.BudgetReservationService;
import com.aistudio.channel.ChannelService;
import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.Disposition;
import com.aistudio.production.contract.ProductionCostPort.Evidence;
import com.aistudio.production.contract.ProductionCostPort.Outcome;
import com.aistudio.production.contract.ProductionCostPort.PricedRequest;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionSelectionPort.Selection;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.LockRetryingTransactions;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionCostIT {
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final String HASH = "a".repeat(64);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired AdminAccountService accounts;
    @Autowired ChannelService channels;
    @Autowired BudgetPolicyService policies;
    @Autowired BudgetControlService control;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductionCostPort costs;
    @Autowired ProductionSelectionPort selection;
    @Autowired LockRetryingTransactions transactions;
    @MockitoSpyBean FakeProvider provider;
    @MockitoSpyBean BudgetReservationService ledger;
    @MockitoSpyBean Clock clock;

    @BeforeAll
    void globalPolicies() throws Exception {
        Actor actor = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("10000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("10000"), "UTC", null, actor);
    }

    @BeforeEach
    void enableSpending() throws Exception {
        if (!control.get().spendingEnabled()) {
            Actor actor = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
            control.setSpending(true, "isolated cost test", control.get().version(), actor);
        }
    }

    @Test
    void sameEffectDispatchesOnceAndSpansSelection() throws Exception {
        long request = request();
        StepOwner discover = start(request, ProductionStage.DISCOVER);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<ProductionCostPort.EffectResult> call = () -> {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Dispatch test did not start");
                }
                return effect(discover, ProductionStage.DISCOVER, "60", "SUCCESS");
            };
            var first = workers.submit(call);
            var second = workers.submit(call);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .anyMatch(result -> result.disposition() == Disposition.RECEIPT);
        }
        verify(provider, times(1)).send(anyLong(), any());
        var replay = effect(discover, ProductionStage.DISCOVER, "60", "SUCCESS").receipt();
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(replay);
        apply(discover, replay.operationId());
        assertThat(costs.readCostState(request).unappliedReceipts()).isEmpty();

        StepOwner select = start(request, ProductionStage.SELECT);
        Selection chosen = new Selection(HASH, "An original topic", "An informative outline", 45);
        assertThatThrownBy(() -> transactions.run(() -> {
            selection.link(select, chosen);
            throw new IllegalStateException("simulated transaction failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("simulated transaction failure");
        assertThat(jdbc.queryForObject("SELECT content_id FROM production_request WHERE id = ?", Long.class, request))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM topic WHERE title = ?", Long.class, chosen.title()))
                .isZero();
        var linked = transactions.execute(() -> {
            var selected = selection.link(select, chosen);
            finishStep(select);
            return selected;
        });
        assertThat(transactions.execute(() -> selection.link(select, chosen))).isEqualTo(linked);
        assertThatThrownBy(() -> transactions.execute(() -> selection.link(select,
                new Selection(HASH, chosen.title(), "Different outline", 45))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT active_run_id FROM content WHERE id = ?", Long.class, linked.contentId()))
                .isEqualTo(linked.runId());
        StepOwner research = start(request, ProductionStage.RESEARCH);
        assertThat(effect(research, ProductionStage.RESEARCH, "41", "SUCCESS").disposition())
                .isEqualTo(Disposition.REJECTED);
        assertThat(effect(research, ProductionStage.RESEARCH, "40", "SUCCESS").disposition())
                .isEqualTo(Disposition.RECEIPT);
        verify(provider, times(2)).send(anyLong(), any());
    }

    @Test
    void unknownPreservesReservationAndBlocksLegacyResolveAndResend() throws Exception {
        long request = request();
        StepOwner owner = start(request, ProductionStage.DISCOVER);
        assertThat(effect(owner, ProductionStage.DISCOVER, "50", "UNKNOWN").disposition())
                .isEqualTo(Disposition.RECOVERY_REQUIRED);
        var restarted = replaceLease(owner);
        assertThatThrownBy(() -> effect(owner, ProductionStage.DISCOVER, "50", "UNKNOWN"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThat(effect(restarted, ProductionStage.DISCOVER, "50", "UNKNOWN").disposition())
                .isEqualTo(Disposition.RECOVERY_REQUIRED);
        verify(provider, times(1)).send(anyLong(), any());
        assertThat(costs.readCostState(request).unresolved()).isTrue();

        long id = operation(request);
        long reservation = jdbc.queryForObject("SELECT reservation_id FROM provider_operation WHERE id = ?", Long.class, id);
        var admin = ApiTestSupport.admin(accounts, jdbc);
        mvc.perform(post("/api/v1/budget-reservations/" + reservation + "/resolve").with(ApiTestSupport.as(admin))
                        .header("Idempotency-Key", ApiTestSupport.uniqueKey("bypass"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_CHARGED\",\"note\":\"bypass attempt\"}"))
                .andExpect(status().is(422));
        assertThat(jdbc.queryForObject("SELECT reserved_remaining_krw FROM budget_reservation WHERE id = ?",
                BigDecimal.class, reservation)).isEqualByComparingTo("50");
        Evidence evidence = new Evidence(Outcome.NOT_EXECUTED, null, "KRW", BigDecimal.ZERO,
                null, null, "FAKE lookup verified no execution");
        var receipt = costs.reconcile(id, "lookup-1", evidence);
        assertThat(costs.reconcile(id, "lookup-1", evidence)).isEqualTo(receipt);
        assertThatThrownBy(() -> jdbc.update("UPDATE provider_operation SET evidence_hash = ? WHERE id = ?",
                "b".repeat(64), id)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("provider operation evidence and receipt are immutable");
        assertThat(costs.readCostState(request).unresolved()).isFalse();
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(receipt);
        assertThatThrownBy(() -> costs.reconcile(id, "lookup-1", new Evidence(
                Outcome.EXECUTED_FAILED, "fake-charge", "KRW", BigDecimal.ONE, null, null, "conflicting charge")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(costs.readCostState(request).spendingBlocked()).isTrue();
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(receipt);
        apply(restarted, id);
    }

    @Test
    void overrunsAreRecordedAndBlockSubsequentSpending() throws Exception {
        long request = request();
        StepOwner owner = start(request, ProductionStage.DISCOVER);
        var result = effect(owner, ProductionStage.DISCOVER, "60", "OVERRUN");
        assertThat(result.disposition()).isEqualTo(Disposition.RECEIPT);
        assertThat(jdbc.queryForObject("SELECT amount_krw FROM cost_log WHERE id = ?", BigDecimal.class,
                result.receipt().ledgerEventId())).isEqualByComparingTo("120");
        apply(owner, result.receipt().operationId());
        assertThat(costs.readCostState(request).spendingBlocked()).isTrue();
        var research = start(request, ProductionStage.RESEARCH);
        assertThat(effect(research, ProductionStage.RESEARCH, "1", "SUCCESS").disposition())
                .isEqualTo(Disposition.REJECTED);
        verify(provider, times(1)).send(anyLong(), any());
    }

    @Test
    void permissionIsNotGrantedWhenSpendingIsDisabled() throws Exception {
        long request = request();
        var admin = ApiTestSupport.admin(accounts, jdbc);
        control.setSpending(false, "test switch off", control.get().version(), Actor.admin(admin.id()));
        StepOwner owner = start(request, ProductionStage.DISCOVER);
        assertThat(effect(owner, ProductionStage.DISCOVER, "1", "SUCCESS").disposition())
                .isEqualTo(Disposition.REJECTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ?",
                Long.class, request)).isZero();
        verify(provider, times(0)).send(anyLong(), any());
    }

    @Test
    void aCommittedReservationCanBeRecoveredOrCancelledBeforeDispatch() throws Exception {
        long request = request();
        var owner = start(request, ProductionStage.DISCOVER);
        doThrow(new IllegalStateException("interrupted before permit")).doCallRealMethod()
                .when(ledgerTarget()).checkProductionDispatch(anyLong(), anyLong());
        assertThatThrownBy(() -> effect(owner, ProductionStage.DISCOVER, "10", "SUCCESS"))
                .hasMessage("interrupted before permit");
        long operation = operation(request);
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operation WHERE id = ?", String.class, operation))
                .isEqualTo("RESERVED");
        verify(provider, times(0)).send(anyLong(), any());
        var restarted = replaceLease(owner);
        jdbc.update("""
                UPDATE production_request SET status = 'RECOVERY_REQUIRED', cancel_requested_at = UTC_TIMESTAMP(6)
                WHERE id = ?
                """, request);
        var receipt = effect(restarted, ProductionStage.DISCOVER, "10", "SUCCESS").receipt();
        assertThat(receipt.outcome()).isEqualTo(Outcome.NOT_EXECUTED);
        assertThat(costs.readCostState(request).unresolved()).isFalse();
        verify(provider, times(0)).send(anyLong(), any());
    }

    @Test
    void persistedEvidenceSurvivesSettlementRollbackAndReplaysWithoutDispatch() throws Exception {
        long request = request();
        var owner = start(request, ProductionStage.DISCOVER);
        doThrow(new IllegalStateException("interrupted before ledger commit")).doCallRealMethod()
                .when(ledgerTarget()).settleProduction(any(), anyBoolean());
        assertThatThrownBy(() -> effect(owner, ProductionStage.DISCOVER, "10", "SUCCESS"))
                .hasMessage("interrupted before ledger commit");
        long operation = operation(request);
        assertThat(jdbc.queryForObject("SELECT evidence_hash FROM provider_operation WHERE id = ?",
                String.class, operation)).hasSize(64);
        assertThat(costs.readCostState(request).unresolved()).isTrue();
        var restarted = replaceLease(owner);
        var receipt = effect(restarted, ProductionStage.DISCOVER, "10", "SUCCESS").receipt();
        assertThat(receipt.operationId()).isEqualTo(operation);
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(receipt);
        assertThatThrownBy(() -> transactions.run(() -> {
            costs.markReceiptApplied(restarted, operation);
            throw new IllegalStateException("interrupted while applying");
        })).hasMessage("interrupted while applying");
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(receipt);
        apply(restarted, operation);
        assertThat(costs.readCostState(request).unappliedReceipts()).isEmpty();
        var applied = jdbc.queryForObject("SELECT applied_at FROM provider_operation WHERE id = ?",
                java.time.LocalDateTime.class, operation);
        transactions.run(() -> costs.markReceiptApplied(restarted, operation));
        assertThat(jdbc.queryForObject("SELECT applied_at FROM provider_operation WHERE id = ?",
                java.time.LocalDateTime.class, operation)).isEqualTo(applied);
        verify(provider, times(1)).send(anyLong(), any());
    }

    @Test
    void recoveredReservationDispatchesOnceAtExecutorDefaultPrice() throws Exception {
        long request = request();
        var owner = start(request, ProductionStage.DISCOVER);
        doThrow(new IllegalStateException("interrupted before permit")).doCallRealMethod()
                .when(ledgerTarget()).checkProductionDispatch(anyLong(), anyLong());
        assertThatThrownBy(() -> effect(owner, ProductionStage.DISCOVER, "1", "SUCCESS"))
                .hasMessage("interrupted before permit");
        long operation = operation(request);
        jdbc.update("UPDATE production_request SET status = 'RECOVERY_REQUIRED' WHERE id = ?", request);
        var restarted = replaceLease(owner);
        var receipt = effect(restarted, ProductionStage.DISCOVER, "1", "SUCCESS").receipt();
        assertThat(receipt.operationId()).isEqualTo(operation);
        assertThat(receipt.outcome()).isEqualTo(Outcome.RESULT_ADOPTED);
        assertThat(effect(restarted, ProductionStage.DISCOVER, "1.0", "SUCCESS").receipt()).isEqualTo(receipt);
        assertThat(jdbc.queryForObject("SELECT amount_krw FROM cost_log WHERE id = ?", BigDecimal.class,
                receipt.ledgerEventId())).isEqualByComparingTo("1");
        verify(provider, times(1)).send(anyLong(), any());
    }

    @Test
    void requestLockSerializesEvidenceAndSettlementWithoutTakingBudgetControlOnRead() throws Exception {
        long request = request();
        var owner = start(request, ProductionStage.DISCOVER);
        assertThat(effect(owner, ProductionStage.DISCOVER, "1", "UNKNOWN").disposition())
                .isEqualTo(Disposition.RECOVERY_REQUIRED);
        long operation = operation(request);
        var evidence = new Evidence(Outcome.NOT_EXECUTED, null, "KRW", BigDecimal.ZERO, null, null, "verified");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var requestLocked = new CountDownLatch(1);
            var releaseRequest = new CountDownLatch(1);
            var finishing = workers.submit(() -> transactions.run(() -> {
                lockRequest(request);
                requestLocked.countDown();
                await(releaseRequest);
                assertThat(costs.readCostState(request).unresolved()).isTrue();
            }));
            var reconciling = new CountDownLatch(1);
            try {
                await(requestLocked);
                var settlement = workers.submit(() -> {
                    reconciling.countDown();
                    return costs.reconcile(operation, "lookup", evidence);
                });
                await(reconciling);
                assertThatThrownBy(() -> settlement.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(jdbc.queryForObject("SELECT evidence_hash FROM provider_operation WHERE id = ?",
                        String.class, operation)).isNull();
                releaseRequest.countDown();
                finishing.get(5, TimeUnit.SECONDS);
                var receipt = settlement.get(5, TimeUnit.SECONDS);
                assertThat(receipt.outcome()).isEqualTo(Outcome.NOT_EXECUTED);

                // A different connection owns the global X lock while the executor-style read runs.
                var controlLocked = new CountDownLatch(1);
                var releaseControl = new CountDownLatch(1);
                var controlWriter = workers.submit(() -> transactions.run(() -> {
                    jdbc.queryForObject("SELECT id FROM budget_control WHERE id = 1 FOR UPDATE", Long.class);
                    controlLocked.countDown();
                    await(releaseControl);
                }));
                try {
                    await(controlLocked);
                    var read = workers.submit(() -> transactions.execute(() -> {
                        lockRequest(request);
                        return costs.readCostState(request);
                    }));
                    var state = read.get(5, TimeUnit.SECONDS);
                    assertThat(state.unresolved()).isFalse();
                    assertThat(state.unappliedReceipts()).containsExactly(receipt);
                } finally {
                    releaseControl.countDown();
                }
                controlWriter.get(5, TimeUnit.SECONDS);
            } finally {
                releaseRequest.countDown();
            }
        }
    }

    private void lockRequest(long request) {
        jdbc.queryForObject("SELECT id FROM production_request WHERE id = ? FOR UPDATE", Long.class, request);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Contract test interrupted", e);
        }
    }

    @Test
    void expiredBudgetPeriodReleasesAnUndispatchedEffect() throws Exception {
        long request = request();
        var owner = start(request, ProductionStage.DISCOVER);
        doThrow(new IllegalStateException("interrupted before permit")).doCallRealMethod()
                .when(ledgerTarget()).checkProductionDispatch(anyLong(), anyLong());
        assertThatThrownBy(() -> effect(owner, ProductionStage.DISCOVER, "10", "SUCCESS"))
                .hasMessage("interrupted before permit");
        doReturn(clock.instant().plus(Duration.ofDays(32))).when(clock).instant();
        var restarted = replaceLease(owner);
        var receipt = effect(restarted, ProductionStage.DISCOVER, "10", "SUCCESS").receipt();
        assertThat(receipt.outcome()).isEqualTo(Outcome.NOT_EXECUTED);
        assertThat(jdbc.queryForObject("SELECT dispatched_at IS NULL FROM provider_operation WHERE id = ?",
                Boolean.class, receipt.operationId())).isTrue();
        assertThat(costs.readCostState(request).unresolved()).isFalse();
        verify(provider, times(0)).send(anyLong(), any());
    }

    private long request() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        long channel = channels.create(ApiTestSupport.uniqueKey("cost-channel"), "en", "UTC", null, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", new BigDecimal("100"), "UTC", null, actor);
        policies.create("CHANNEL", channel, null, "DAY", new BigDecimal("500"), "UTC", null, actor);
        String key = ApiTestSupport.uniqueKey("cost-request");
        mvc.perform(post("/api/v1/production-requests").with(ApiTestSupport.as(admin))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channel + "\",\"contentType\":\"SHORTS\"}"))
                .andExpect(status().isCreated());
        long id = jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
        jdbc.update("DELETE FROM production_discovery WHERE production_request_id = ?", id);
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-fixed-1' WHERE id = ?", id);
        return id;
    }

    private BudgetReservationService ledgerTarget() {
        return AopTestUtils.getUltimateTargetObject(ledger);
    }

    private StepOwner start(long request, ProductionStage stage) {
        jdbc.update("""
                UPDATE production_request SET status = 'RUNNING', lease_owner = 'test-worker',
                    lease_epoch = 1, lease_until = UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE WHERE id = ?
                """, request);
        long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ? AND stage = ?",
                Long.class, request, stage.name());
        jdbc.update("UPDATE production_step SET status = 'RUNNING' WHERE id = ?", step);
        return new StepOwner(request, step, "test-worker", 1);
    }

    private StepOwner replaceLease(StepOwner owner) {
        jdbc.update("UPDATE production_request SET lease_epoch = 2, lease_owner = 'restarted' WHERE id = ?",
                owner.requestId());
        return new StepOwner(owner.requestId(), owner.stepId(), "restarted", 2);
    }

    private long operation(long request) {
        return jdbc.queryForObject("SELECT id FROM provider_operation WHERE production_request_id = ?", Long.class, request);
    }

    private ProductionCostPort.EffectResult effect(StepOwner owner, ProductionStage stage, String amount, String scenario) {
        return costs.executeEffect(owner, EffectKey.forInput(owner.requestId(), stage, "video", 1), HASH,
                new PricedRequest("KRW", new BigDecimal(amount), scenario));
    }

    private void apply(StepOwner owner, long id) {
        transactions.run(() -> {
            costs.markReceiptApplied(owner, id);
            finishStep(owner);
        });
    }

    private void finishStep(StepOwner owner) {
        jdbc.update("UPDATE production_step SET status = 'SUCCEEDED' WHERE id = ?", owner.stepId());
    }
}
