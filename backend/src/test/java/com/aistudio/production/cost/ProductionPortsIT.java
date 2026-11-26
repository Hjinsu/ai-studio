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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;
import com.aistudio.audit.AuditService.Actor;
import com.aistudio.budget.BudgetControlService;
import com.aistudio.budget.BudgetPolicyService;
import com.aistudio.channel.ChannelService;
import com.aistudio.channel.ChannelSettings;
import com.aistudio.production.contract.DiscoveryExecutionPort;
import com.aistudio.production.contract.DiscoveryProvider;
import com.aistudio.production.contract.ProductionDiscovery.Candidate;
import com.aistudio.production.contract.ProductionDiscovery.Block;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.EvidencePlan;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionDiscovery.Scores;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.Evidence;
import com.aistudio.production.contract.ProductionCostPort.Outcome;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
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
    @Autowired DiscoveryExecutionPort discovery;
    @Autowired ProductionSelectionPort selection;
    @MockitoSpyBean FakeProvider provider;
    @MockitoBean DiscoveryProvider discoveryProvider;

    @BeforeAll
    void enableIsolatedBudgets() {
        Actor actor = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        policies.create("SYSTEM", null, null, "DAY", new BigDecimal("1000"), "UTC", null, actor);
        policies.create("SYSTEM", null, null, "MONTH", new BigDecimal("1000"), "UTC", null, actor);
        control.setSpending(true, "isolated port integration", control.get().version(), actor);
    }

    @AfterEach
    void releaseDiscoveryFixtureLeases() {
        jdbc.update("UPDATE production_request SET lease_owner = NULL, lease_until = NULL WHERE lease_owner = 'discovery-test'");
    }

    @Test
    void replaysDiscoveryAfterEvidenceCommitAndLinksUsingTheSnapshot() throws Exception {
        long request = newDiscoveryRequest();
        StepOwner owner = own(request, "DISCOVER", 1);
        var input = discovery.input(request);
        var response = sampleResponse(input);
        long channel = jdbc.queryForObject("SELECT channel_id FROM production_request WHERE id = ?", Long.class, request);
        Actor actor = Actor.admin(ApiTestSupport.admin(accounts, jdbc).id());
        channels.update(channel, channels.get(channel).channel().version(), null, "ko", null, actor);
        channels.createConfigRevision(channel, 1, new ChannelSettings("SHORTS", 55, 62, 480, 720,
                "Updated strategy", null, null, null, true,
                Map.of("discovery", Map.of("prohibitedTopics", List.of("lenses")))), "New discovery settings", actor);
        assertThat(discovery.input(request)).isEqualTo(input);
        doAnswer(call -> response).when(discoveryProvider).generate(input);
        jdbc.execute("""
                CREATE TRIGGER it_interrupt_discovery_settle BEFORE UPDATE ON provider_operation FOR EACH ROW
                BEGIN
                  IF NEW.status = 'SETTLED' AND NEW.production_request_id = %d THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'discovery settlement interrupted';
                  END IF;
                END
                """.formatted(request));
        try {
            assertThatThrownBy(() -> discovery.discover(owner, input)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM production_discovery d JOIN provider_operation o ON o.id = d.provider_operation_id
                    WHERE d.production_request_id = ? AND d.response_json IS NOT NULL AND o.evidence_hash IS NOT NULL
                    """, Integer.class, request)).isEqualTo(1);
            assertThat(costs.readCostState(request).unresolved()).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER it_interrupt_discovery_settle");
        }
        StepOwner restarted = own(request, "DISCOVER", 2);
        var receipt = discovery.discover(restarted, input).receipt();
        assertThat(discovery.discover(restarted, input).receipt()).isEqualTo(receipt);
        assertThat(discovery.response(restarted, receipt)).isEqualTo(response);
        assertThatThrownBy(() -> discovery.response(owner, receipt)).isInstanceOf(ApiException.class);
        verify(discoveryProvider).generate(input);
        var assessments = sampleAssessments(response);
        transactions.run(() -> {
            discovery.applyEvaluation(restarted, receipt, assessments);
            jdbc.update("UPDATE production_step SET status = 'SUCCEEDED' WHERE id = ?", restarted.stepId());
        });
        assertThat(costs.readCostState(request).unappliedReceipts()).isEmpty();
        StepOwner selecting = own(request, "SELECT", 3);
        var candidate = response.candidates().getFirst();
        var link = new ProductionSelectionPort.Selection(receipt.resultHash(), candidate.title(),
                candidate.centralQuestion(), 45, input.language());
        assertThatThrownBy(() -> transactions.run(() -> {
            var batch = discovery.lockForSelection(selecting);
            assertThat(discovery.catalog(request, response.candidates())).isEmpty();
            selection.link(selecting, link);
            discovery.recordSelection(selecting, batch.version(), 1, "First ranked", assessments);
            throw new IllegalStateException("select completion interrupted");
        })).hasMessage("select completion interrupted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isZero();
        transactions.run(() -> {
            var batch = discovery.lockForSelection(selecting);
            var selected = selection.link(selecting, link);
            assertThat(selection.link(selecting, link)).isEqualTo(selected);
            discovery.recordSelection(selecting, batch.version(), 1, "First ranked", assessments);
            assertThat(discovery.catalog(request, response.candidates())).anyMatch(row ->
                    Long.toString(selected.contentId()).equals(row.contentId()));
        });
        assertThat(jdbc.queryForObject("""
                SELECT r.language FROM production_request p JOIN content c ON c.id = p.content_id
                JOIN content_revision r ON r.id = c.current_revision_id WHERE p.id = ?
                """, String.class, request)).isEqualTo("en");
    }

    @Test
    void recordsFailedResponseCostAndQuarantinesUnknownDiscoveryWithoutResending() throws Exception {
        long malformed = newDiscoveryRequest();
        var malformedInput = discovery.input(malformed);
        var malformedOwner = own(malformed, "DISCOVER", 1);
        doAnswer(call -> new Response(1, List.of())).when(discoveryProvider).generate(malformedInput);
        var failed = discovery.discover(malformedOwner, malformedInput).receipt();
        assertThat(failed.outcome()).isEqualTo(Outcome.EXECUTED_FAILED);
        assertThat(discovery.discover(malformedOwner, malformedInput).receipt()).isEqualTo(failed);
        assertThat(jdbc.queryForObject("""
                SELECT b.recognized_cost_krw FROM provider_operation o
                JOIN budget_reservation b ON b.id = o.reservation_id WHERE o.id = ?
                """, BigDecimal.class, failed.operationId())).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT response_json FROM production_discovery WHERE production_request_id = ?",
                String.class, malformed)).isNull();
        verify(discoveryProvider).generate(malformedInput);

        long unknown = newDiscoveryRequest();
        var unknownInput = discovery.input(unknown);
        var unknownOwner = own(unknown, "DISCOVER", 1);
        doAnswer(call -> { throw new IllegalStateException("FAKE result unavailable"); })
                .when(discoveryProvider).generate(unknownInput);
        assertThat(discovery.discover(unknownOwner, unknownInput).disposition())
                .isEqualTo(ProductionCostPort.Disposition.RECOVERY_REQUIRED);
        assertThat(discovery.discover(own(unknown, "DISCOVER", 2), unknownInput).disposition())
                .isEqualTo(ProductionCostPort.Disposition.RECOVERY_REQUIRED);
        assertThat(costs.readCostState(unknown).unresolved()).isTrue();
        verify(discoveryProvider).generate(unknownInput);
    }

    @Test
    void concurrentSelectionReadsTheWinnerBeforeLinkingOnTheSameChannel() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        long channel = channels.create(ApiTestSupport.uniqueKey("selection-race"), "en", "UTC", null, actor)
                .channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", BigDecimal.TEN, "UTC", null, actor);
        long first = postDiscoveryRequest(admin, channel);
        long second = postDiscoveryRequest(admin, channel);
        var firstOwner = prepareSelection(first);
        var secondOwner = prepareSelection(second);
        var winnerLinked = new CountDownLatch(1);
        var releaseWinner = new CountDownLatch(1);
        var contenderStarted = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var winner = threads.submit(() -> transactions.run(() -> {
                var batch = discovery.lockForSelection(firstOwner);
                var candidate = batch.candidates().getFirst().candidate();
                assertThat(discovery.catalog(first, List.of(candidate))).isEmpty();
                selection.link(firstOwner, new ProductionSelectionPort.Selection(
                        "f".repeat(64), candidate.title(), candidate.centralQuestion(), 45));
                discovery.recordSelection(firstOwner, batch.version(), 1, "Winner", batch.candidates());
                winnerLinked.countDown();
                await(releaseWinner);
            }));
            try {
                await(winnerLinked);
                var contender = threads.submit(() -> transactions.run(() -> {
                    contenderStarted.countDown();
                    var batch = discovery.lockForSelection(secondOwner);
                    var candidate = batch.candidates().getFirst().candidate();
                    assertThat(discovery.catalog(second, List.of(candidate)))
                            .anyMatch(entry -> entry.contentId() != null && candidate.title().equals(entry.title()));
                    selection.link(secondOwner, new ProductionSelectionPort.Selection(
                            "f".repeat(64), candidate.title(), candidate.centralQuestion(), 45));
                }));
                await(contenderStarted);
                assertThatThrownBy(() -> contender.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseWinner.countDown();
                winner.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ApiException.class);
            } finally {
                releaseWinner.countDown();
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(1);
        transactions.run(() -> {
            var batch = discovery.lockForSelection(secondOwner);
            var blocked = batch.candidates().stream()
                    .map(item -> new EvaluatedCandidate(item.candidateId(), item.candidate(), item.scores(), item.totalScore(),
                            List.of(Block.DUPLICATE), List.of(), "Matched the committed catalog"))
                    .toList();
            discovery.recordExhaustedSelection(secondOwner, batch.version(), blocked);
            assertThat(discovery.lockForSelection(secondOwner).candidates()).isEqualTo(blocked);
        });
        assertThat(jdbc.queryForObject("""
                SELECT evaluation_json <> recheck_json FROM production_discovery WHERE production_request_id = ?
                """, Boolean.class, second)).isTrue();
    }

    private StepOwner prepareSelection(long request) {
        var input = discovery.input(request);
        var response = sampleResponse(input);
        org.mockito.Mockito.when(discoveryProvider.generate(input)).thenReturn(response);
        var owner = own(request, "DISCOVER", 1);
        var receipt = discovery.discover(owner, input).receipt();
        var assessments = sampleAssessments(response);
        transactions.run(() -> {
            discovery.applyEvaluation(owner, receipt, assessments);
            jdbc.update("UPDATE production_step SET status = 'SUCCEEDED' WHERE id = ?", owner.stepId());
        });
        return own(request, "SELECT", 2);
    }

    private static List<EvaluatedCandidate> sampleAssessments(Response response) {
        return java.util.stream.IntStream.range(0, response.candidates().size())
                .mapToObj(i -> new EvaluatedCandidate(i + 1, response.candidates().get(i),
                        response.candidates().get(i).suggestedScores(), 52, List.of(), List.of(), "Test assessment"))
                .toList();
    }

    private static Response sampleResponse(Input input) {
        return new Response(input.rulesVersion(), java.util.stream.IntStream.rangeClosed(1, input.candidateCount())
                .mapToObj(i -> new Candidate("Light " + i, "Why does light bend?", "Light and lenses " + i,
                        "Compare lenses " + i, List.of("Show a lens", "Trace the rays"),
                        List.of(new EvidencePlan("Physics textbook", "Refraction diagram")), List.of(),
                        new Scores(15, 10, 10, 7, 7, 3), "Visual explanation")).toList());
    }

    private StepOwner own(long request, String stage, long epoch) {
        jdbc.update("""
                UPDATE production_request SET status = 'RUNNING', lease_owner = 'discovery-test',
                    lease_epoch = ?, lease_until = UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE WHERE id = ?
                """, epoch, request);
        jdbc.update("UPDATE production_step SET status = 'RUNNING' WHERE production_request_id = ? AND stage = ?",
                request, stage);
        long step = jdbc.queryForObject("SELECT id FROM production_step WHERE production_request_id = ? AND stage = ?",
                Long.class, request, stage);
        return new StepOwner(request, step, "discovery-test", epoch);
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
        long id = newDiscoveryRequest();
        // This suite exercises persisted V2-01 requests while the V2-02 executor is pending.
        jdbc.update("DELETE FROM production_discovery WHERE production_request_id = ?", id);
        jdbc.update("UPDATE production_request SET pipeline_version = 'v2-fixed-1' WHERE id = ?", id);
        return id;
    }

    private long newDiscoveryRequest() throws Exception {
        var admin = ApiTestSupport.admin(accounts, jdbc);
        Actor actor = Actor.admin(admin.id());
        long channel = channels.create(ApiTestSupport.uniqueKey("ports-channel"), "en", "UTC", null, actor).channel().id();
        policies.create("CONTENT_TYPE", channel, "SHORTS", "LIFETIME", BigDecimal.TEN, "UTC", null, actor);
        return postDiscoveryRequest(admin, channel);
    }

    private long postDiscoveryRequest(com.aistudio.security.AdminPrincipal admin, long channel) throws Exception {
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
