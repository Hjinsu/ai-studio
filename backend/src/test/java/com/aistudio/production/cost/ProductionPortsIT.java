package com.aistudio.production.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.channel.ChannelService;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.Evidence;
import com.aistudio.production.contract.ProductionCostPort.Outcome;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.execution.ProductionExecutor;
import com.aistudio.security.AdminAccountService;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;
import com.aistudio.shared.db.LockRetryingTransactions;

@Tag("integration")
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionPortsIT {
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();

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
    @Autowired ProductionExecutor executor;
    @Autowired ProductionCostPort costs;
    @Autowired LockRetryingTransactions transactions;
    @MockitoSpyBean FakeProvider provider;

    @BeforeAll
    void enableIsolatedBudgets() {
        Actor actor = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "UTC", null, actor);
        control.setSpending(true, "isolated port integration", control.get().version(), actor);
    }

    @Test
    void completesElevenStagesWithEightSettledEffectsAndPreservesLateConflicts() throws Exception {
        long request = newRequest();
        run("normal");

        assertThat(stepStages(request)).containsExactlyElementsOf(
                java.util.Arrays.stream(ProductionStage.values()).map(Enum::name).toList());
        assertThat(stepStates(request)).containsOnly("SUCCEEDED").hasSize(11);
        assertThat(jdbc.queryForList("""
                SELECT s.stage FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? ORDER BY o.id
                """, String.class, request)).containsExactly(
                        "DISCOVER", "RESEARCH", "SCRIPT", "SCENES", "IMAGE", "TTS", "METADATA", "QC");
        assertThat(jdbc.queryForObject("""
                SELECT SUM(r.recognized_cost_krw)
                FROM provider_operation o JOIN budget_reservation r ON r.id = o.reservation_id
                WHERE o.production_request_id = ?
                """, BigDecimal.class, request)).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_operation WHERE production_request_id = ?
                    AND status = 'SETTLED' AND applied_at IS NOT NULL AND dispatched_at IS NOT NULL
                """, Integer.class, request)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_request p
                JOIN content c ON c.id = p.content_id AND c.channel_id = p.channel_id
                JOIN topic t ON t.id = c.topic_id AND t.channel_id = c.channel_id
                JOIN content_revision v ON v.id = c.current_revision_id AND v.content_id = c.id
                JOIN content_run r ON r.id = p.run_id AND r.content_id = c.id AND r.content_revision_id = v.id
                WHERE p.id = ? AND v.channel_config_id = p.channel_config_id
                """, Integer.class, request)).isEqualTo(1);
        assertClosed(request, "SUCCEEDED");
        verify(provider, times(8)).send(anyLong(), any());

        long first = operation(request, "DISCOVER");
        var before = jdbc.queryForMap("""
                SELECT evidence_hash, evidence_json, receipt_event_id, receipt_at, applied_at
                FROM provider_operation WHERE id = ?
                """, first);
        assertConflict(first);
        assertThat(costs.readCostState(request).spendingBlocked()).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT evidence_hash, evidence_json, receipt_event_id, receipt_at, applied_at
                FROM provider_operation WHERE id = ?
                """, first)).isEqualTo(before);
        assertClosed(request, "SUCCEEDED");
        assertThat(executor.runOnce("late-conflict")).isFalse();
        verify(provider, times(8)).send(anyLong(), any());
    }

    @Test
    void unresolvedAndUnappliedCostsPreventFinishAndRestartDoesNotResend() throws Exception {
        long request = newRequest();
        var sends = new AtomicInteger();
        doAnswer(invocation -> {
            if (sends.incrementAndGet() == 2) {
                throw new AssertionError("simulated crash after dispatch commit");
            }
            return invocation.callRealMethod();
        }).when(provider).send(anyLong(), any());
        assertThatThrownBy(() -> executor.runOnce("before-restart"))
                .isInstanceOf(AssertionError.class).hasMessage("simulated crash after dispatch commit");
        long operation = operation(request, "RESEARCH");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operation WHERE id = ?", String.class, operation))
                .isEqualTo("DISPATCHED");
        assertThat(executor.runOnce("before-expiry")).isFalse();
        jdbc.update("UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?",
                request);
        run("after-restart");
        assertOpen(request);
        verify(provider, times(2)).send(anyLong(), any());
        assertThat(costs.readCostState(request).unresolved()).isTrue();

        // Present terminal steps to exercise the completion gate itself, independent of the planner's recovery gate.
        jdbc.update("UPDATE production_step SET status = 'SUCCEEDED', retry_at = NULL WHERE production_request_id = ?",
                request);
        run("unresolved-finish");
        assertOpen(request);

        var receipt = costs.reconcile(operation, "confirmed", new Evidence(Outcome.RESULT_ADOPTED, "fake-lookup",
                "KRW", BigDecimal.ONE, "fake-result", "a".repeat(64), "lookup confirmed the result"));
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(receipt);
        makeDue(request);
        run("unapplied-finish");
        assertOpen(request);
        assertThat(costs.readCostState(request).unappliedReceipts()).containsExactly(receipt);

        jdbc.update("""
                UPDATE production_step SET status = 'RECOVERY_REQUIRED', retry_at = NULL
                WHERE production_request_id = ? AND stage = 'RESEARCH'
                """, request);
        makeDue(request);
        run("apply-after-restart");
        assertClosed(request, "SUCCEEDED");
        assertThat(costs.readCostState(request).unappliedReceipts()).isEmpty();
        verify(provider, times(2)).send(anyLong(), any());
    }

    @Test
    void cancellationAndReconciliationSerializeBeforeCancelCompletes() throws Exception {
        long request = newRequest();
        interruptResearch();
        run("before-cancel");
        long operation = operation(request, "RESEARCH");
        var admin = ApiTestSupport.admin(accounts, jdbc);
        var gate = new CountDownLatch(1);
        try (var connections = Executors.newFixedThreadPool(2)) {
            var cancelling = connections.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                // The fixture creates the request with this administrator.
                mvc.perform(post("/api/v1/production-requests/" + request + "/cancel")
                        .with(ApiTestSupport.as(admin))).andExpect(status().isOk());
                return null;
            });
            var reconciling = connections.submit(() -> {
                assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
                return costs.reconcile(operation, "cancel-lookup", noExecution());
            });
            gate.countDown();
            cancelling.get(10, TimeUnit.SECONDS);
            assertThat(reconciling.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.NOT_EXECUTED);
        }
        makeDue(request);
        run("cancel-after-reconcile");
        assertClosed(request, "CANCELLED");
        assertThat(costs.readCostState(request).unappliedReceipts()).isEmpty();
        verify(provider, times(2)).send(anyLong(), any());
    }

    @Test
    void completionFailureRollsBackRunAndConflictingEvidencePreventsRetrySuccess() throws Exception {
        long request = newRequest();
        failCompletion(request);
        assertOpen(request);
        assertThat(stepStates(request)).containsOnly("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT finished_at IS NULL FROM content_run WHERE id = ("
                + "SELECT run_id FROM production_request WHERE id = ?)", Boolean.class, request)).isTrue();

        try (var otherConnection = Executors.newSingleThreadExecutor()) {
            otherConnection.submit(() -> assertConflict(operation(request, "DISCOVER"))).get(10, TimeUnit.SECONDS);
        }
        makeDue(request);
        run("conflict-before-completion");
        assertOpen(request);
        assertThat(costs.readCostState(request).spendingBlocked()).isTrue();
        verify(provider, times(8)).send(anyLong(), any());
    }

    @Test
    void completionCanRetryAfterEntireRollback() throws Exception {
        long request = newRequest();
        failCompletion(request);
        assertOpen(request);
        makeDue(request);
        run("completion-retry");
        assertClosed(request, "SUCCEEDED");
        verify(provider, times(8)).send(anyLong(), any());
    }

    @Test
    void conflictingEvidenceWaitsForAnInProgressCompletion() throws Exception {
        long request = newRequest();
        failCompletion(request);
        makeDue(request);
        long content = jdbc.queryForObject("SELECT content_id FROM production_request WHERE id = ?", Long.class, request);
        var contentLocked = new CountDownLatch(1);
        var releaseContent = new CountDownLatch(1);
        try (var connections = Executors.newFixedThreadPool(3)) {
            var holder = connections.submit(() -> transactions.run(() -> {
                jdbc.queryForObject("SELECT id FROM content WHERE id = ? FOR UPDATE", Long.class, content);
                contentLocked.countDown();
                await(releaseContent);
            }));
            try {
                await(contentLocked);
                var finish = connections.submit(() -> run("finishing-before-conflict"));
                awaitRequestLock(request);
                var conflict = connections.submit(() -> assertConflict(operation(request, "DISCOVER")));
                assertThatThrownBy(() -> conflict.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                releaseContent.countDown();
                finish.get(10, TimeUnit.SECONDS);
                conflict.get(10, TimeUnit.SECONDS);
            } finally {
                releaseContent.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertClosed(request, "SUCCEEDED");
        assertThat(costs.readCostState(request).spendingBlocked()).isTrue();
        verify(provider, times(8)).send(anyLong(), any());
    }

    private void awaitRequestLock(long request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int lockedSamples = 0;
        do {
            try {
                transactions.run(() -> jdbc.queryForObject(
                        "SELECT id FROM production_request WHERE id = ? FOR UPDATE NOWAIT", Long.class, request));
                lockedSamples = 0;
            } catch (DataAccessException failure) {
                if (!(failure.getMostSpecificCause() instanceof SQLException sql) || sql.getErrorCode() != 1205) {
                    throw failure;
                }
                if (++lockedSamples == 2) {
                    return;
                }
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Completion did not hold the request lock while waiting for Content");
    }

    private void failCompletion(long request) {
        jdbc.execute("""
                CREATE TRIGGER it_fail_production_completion BEFORE UPDATE ON production_request FOR EACH ROW
                BEGIN
                  IF NEW.id = %d AND NEW.status = 'SUCCEEDED' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected completion failure';
                  END IF;
                END
                """.formatted(request));
        try {
            run("injected-failure");
        } finally {
            jdbc.execute("DROP TRIGGER it_fail_production_completion");
        }
    }

    private void interruptResearch() {
        var sends = new AtomicInteger();
        doAnswer(invocation -> sends.incrementAndGet() == 2 ? null : invocation.callRealMethod())
                .when(provider).send(anyLong(), any());
    }

    private void assertConflict(long operation) {
        assertThatThrownBy(() -> costs.reconcile(operation, "conflicting-lookup", noExecution()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    private static Evidence noExecution() {
        return new Evidence(Outcome.NOT_EXECUTED, null, "KRW", BigDecimal.ZERO, null, null, "lookup evidence");
    }

    private long newRequest() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        long channel = channels.create(ApiTestSupport.uniqueKey("ports-channel"), "en", "UTC", null, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", BigDecimal.TEN, "UTC", null, actor);
        String key = ApiTestSupport.uniqueKey("ports-request");
        mvc.perform(post("/api/v1/production-requests").with(ApiTestSupport.as(admin))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channel + "\",\"contentType\":\"SHORTS\"}"))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
    }

    private void run(String owner) {
        assertThat(executor.runOnce(owner)).isTrue();
    }

    private long operation(long request, String stage) {
        return jdbc.queryForObject("""
                SELECT o.id FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
                WHERE o.production_request_id = ? AND s.stage = ?
                """, Long.class, request, stage);
    }

    private void makeDue(long request) {
        jdbc.update("UPDATE production_step SET retry_at = NULL WHERE production_request_id = ?", request);
    }

    private List<String> stepStages(long request) {
        return jdbc.queryForList("""
                SELECT stage FROM production_step WHERE production_request_id = ? ORDER BY id
                """, String.class, request);
    }

    private List<String> stepStates(long request) {
        return jdbc.queryForList("SELECT status FROM production_step WHERE production_request_id = ?", String.class, request);
    }

    private void assertOpen(long request) {
        assertThat(jdbc.queryForMap("""
                SELECT p.status AS request_status, r.status AS run_status, c.status AS content_status,
                    (c.active_run_id = r.id) AS active
                FROM production_request p JOIN content c ON c.id = p.content_id
                JOIN content_run r ON r.id = p.run_id WHERE p.id = ?
                """, request)).containsEntry("request_status", "RECOVERY_REQUIRED")
                .containsEntry("run_status", "RUNNING").containsEntry("content_status", "PRODUCING")
                .containsEntry("active", 1);
    }

    private void assertClosed(long request, String status) {
        assertThat(jdbc.queryForMap("""
                SELECT p.status AS request_status, r.status AS run_status, c.status AS content_status, c.active_run_id
                FROM production_request p JOIN content c ON c.id = p.content_id
                JOIN content_run r ON r.id = p.run_id WHERE p.id = ?
                """, request)).containsEntry("request_status", status).containsEntry("run_status", status)
                .containsEntry("content_status", "DRAFT").containsEntry("active_run_id", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
