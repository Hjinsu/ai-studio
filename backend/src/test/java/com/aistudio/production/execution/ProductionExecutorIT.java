package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.aistudio.TestDatabases;
import com.aistudio.content.ContentFixtures;
import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.execution.ExecutionFakes.Behavior;
import com.aistudio.production.execution.ExecutionFakes.Crash;
import com.aistudio.production.execution.ExecutionFakes.FakeCost;
import com.aistudio.production.execution.ExecutionFakes.FakeSelection;
import com.aistudio.production.execution.ExecutionFakes.ScriptedWork;

/**
 * Fixed-stage executor against an isolated MariaDB (V1~V9) with port doubles. Time-dependent cases move
 * lease_until/retry_at on the DB clock instead of sleeping.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "ai-studio.production.executor.enabled=true",
        "ai-studio.production.executor.auto-start=false",
        "ai-studio.production.executor.lease-duration=PT5S",
        "ai-studio.production.executor.poll-interval=PT0.1S",
        "ai-studio.production.executor.recovery-recheck=PT1H",
        "ai-studio.production.executor.retry-base=PT1S",
        "ai-studio.production.executor.retry-cap=PT4S" })
@ActiveProfiles("test")
@Import(ProductionExecutorIT.Ports.class)
class ProductionExecutorIT {
    private static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final int EFFECT_STAGES = 8;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    @TestConfiguration
    static class Ports {
        @Bean
        FakeCost fakeCost(JdbcTemplate jdbc) {
            return new FakeCost(jdbc);
        }

        @Bean
        FakeSelection fakeSelection(JdbcTemplate jdbc) {
            return new FakeSelection(jdbc);
        }

        @Bean
        ScriptedWork scriptedWork() {
            return new ScriptedWork();
        }
    }

    @Autowired ProductionExecutor executor;
    @Autowired ProductionWorkerPool pool;
    @Autowired FakeCost cost;
    @Autowired FakeSelection selection;
    @Autowired ScriptedWork work;
    @Autowired JdbcTemplate jdbc;
    ContentFixtures fixtures;

    @BeforeEach
    void isolate() {
        fixtures = new ContentFixtures(jdbc);
        cost.reset();
        selection.reset();
        work.reset();
        // earlier tests' requests must not hold leases or be claimable
        jdbc.update("""
                UPDATE production_request SET status = 'FAILED', lease_owner = NULL, lease_until = NULL
                WHERE status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED')
                """);
    }

    @AfterEach
    void stopPool() {
        pool.stop();
    }

    // ------------------------------------------------------------------ fixtures and helpers

    long newRequest() {
        long admin = fixtures.admin();
        long channel = fixtures.channel();
        long config = fixtures.config(channel, 1);
        String key = "exec-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO production_request (channel_id, channel_config_id, requested_by, content_type,
                    pipeline_version, request_key, request_hash, limit_krw, created_at)
                VALUES (?, ?, ?, 'SHORTS', 'v2-fixed-1', ?, ?, 2000, UTC_TIMESTAMP(6))
                """, channel, config, admin, key, EffectRequestHash.sha256(key));
        long id = jdbc.queryForObject("SELECT id FROM production_request WHERE request_key = ?", Long.class, key);
        for (ProductionStage stage : ProductionStage.values()) {
            jdbc.update("INSERT INTO production_step (production_request_id, stage) VALUES (?, ?)", id, stage.name());
        }
        return id;
    }

    int drain(String owner) {
        int claims = 0;
        while (executor.runOnce(owner)) {
            if (++claims > 50) {
                throw new AssertionError("executor did not settle");
            }
        }
        return claims;
    }

    String status(long id) {
        return jdbc.queryForObject("SELECT status FROM production_request WHERE id = ?", String.class, id);
    }

    Map<String, Object> request(long id) {
        return jdbc.queryForMap("SELECT * FROM production_request WHERE id = ?", id);
    }

    Map<String, Object> step(long id, ProductionStage stage) {
        return jdbc.queryForMap("SELECT * FROM production_step WHERE production_request_id = ? AND stage = ?", id,
                stage.name());
    }

    List<String> stepStatuses(long id) {
        return jdbc.queryForList("SELECT status FROM production_step WHERE production_request_id = ? ORDER BY id",
                String.class, id);
    }

    String runStatus(long id) {
        return jdbc.queryForObject("""
                SELECT r.status FROM production_request p JOIN content_run r ON r.id = p.run_id WHERE p.id = ?
                """, String.class, id);
    }

    Map<String, Object> content(long id) {
        return jdbc.queryForMap("""
                SELECT c.status, c.active_run_id FROM production_request p JOIN content c ON c.id = p.content_id
                WHERE p.id = ?
                """, id);
    }

    void expireLease(long id) {
        jdbc.update("""
                UPDATE production_request SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE id = ? AND lease_owner IS NOT NULL
                """, id);
    }

    void makeDue(long id) {
        jdbc.update("""
                UPDATE production_step SET retry_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
                WHERE production_request_id = ? AND retry_at IS NOT NULL
                """, id);
    }

    void cancel(long id) {
        jdbc.update("UPDATE production_request SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
    }

    int validLeases() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_request WHERE lease_owner IS NOT NULL AND lease_until > UTC_TIMESTAMP(6)
                """, Integer.class);
    }

    void assertRunFinished(long id, String runStatus) {
        assertThat(runStatus(id)).isEqualTo(runStatus);
        Map<String, Object> content = content(id);
        assertThat(content.get("status")).isEqualTo("DRAFT");
        assertThat(content.get("active_run_id")).isNull();
    }

    void assertLeaseReleased(long id) {
        Map<String, Object> row = request(id);
        assertThat(row.get("lease_owner")).isNull();
        assertThat(row.get("lease_until")).isNull();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void fakeRunExecutesElevenStagesInOrderAndFinishesTheRun() {
        long id = newRequest();

        assertThat(drain("w1")).isEqualTo(1);

        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertLeaseReleased(id);
        assertThat(stepStatuses(id)).hasSize(11).containsOnly("SUCCEEDED");
        assertThat(jdbc.queryForList("""
                SELECT stage FROM production_step WHERE production_request_id = ? ORDER BY started_at, id
                """, String.class, id)).containsExactly(java.util.Arrays.stream(ProductionStage.values())
                .map(Enum::name).toArray(String[]::new));
        assertThat(jdbc.queryForList("SELECT attempts FROM production_step WHERE production_request_id = ?",
                Integer.class, id)).containsOnly(1);
        assertThat(step(id, ProductionStage.SCRIPT).get("result_ref")).isEqualTo("fake-result:SCRIPT");
        assertThat(step(id, ProductionStage.RENDER).get("result_ref")).isEqualTo("local:RENDER:1");
        assertThat((String) step(id, ProductionStage.SELECT).get("result_ref")).startsWith("run:");
        assertRunFinished(id, "SUCCEEDED");
        assertThat(cost.dispatches.get()).isEqualTo(EFFECT_STAGES);
        assertThat(cost.applications.get()).isEqualTo(EFFECT_STAGES);
        assertThat(selection.links.get()).isEqualTo(1);
        // the effect key is the common contract identity of the step's single logical effect
        assertThat(cost.ops).containsKey(EffectKey.forInput(id, ProductionStage.DISCOVER, "video", 1));
        // terminal requests are not claimed again
        assertThat(executor.runOnce("w1")).isFalse();
    }

    @Test
    void workerPoolRunsRequestsInTheBackground() throws Exception {
        long first = newRequest();
        long second = newRequest();
        pool.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!(status(first).equals("SUCCEEDED") && status(second).equals("SUCCEEDED"))) {
            assertThat(System.nanoTime()).as("pool finishes both requests").isLessThan(deadline);
            Thread.sleep(50);
        }
        pool.stop();
        assertThat(pool.isRunning()).isFalse();
        assertThat(validLeases()).isZero();
    }

    // ------------------------------------------------------------------ claim, lease and fencing

    @Test
    void threeConcurrentClaimsGetAtMostTwoValidLeases() throws Exception {
        newRequest();
        newRequest();
        newRequest();
        ExecutorService threads = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<ProductionExecutor.Lease>>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String owner = "claimer-" + i;
                results.add(threads.submit(() -> {
                    start.await();
                    return executor.claim(owner);
                }));
            }
            start.countDown();
            List<ProductionExecutor.Lease> granted = new ArrayList<>();
            for (var result : results) {
                result.get(30, TimeUnit.SECONDS).ifPresent(granted::add);
            }
            assertThat(granted).hasSize(2);
            assertThat(granted.stream().map(ProductionExecutor.Lease::requestId).distinct()).hasSize(2);
            assertThat(validLeases()).isEqualTo(2);
            assertThat(granted).allSatisfy(lease -> assertThat(lease.epoch()).isEqualTo(1));
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void oneRequestIsLeasedByOneWorkerAtATime() throws Exception {
        long id = newRequest();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = threads.submit(() -> {
                start.await();
                return executor.claim("a");
            });
            var b = threads.submit(() -> {
                start.await();
                return executor.claim("b");
            });
            start.countDown();
            long granted = java.util.stream.Stream.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))
                    .filter(Optional::isPresent).count();
            assertThat(granted).isEqualTo(1);
            assertThat(request(id).get("lease_epoch")).isEqualTo(1L);
            assertThat(status(id)).isEqualTo("RUNNING");
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void expiredOrReplacedWorkerCannotChangeState() {
        long id = newRequest();
        ProductionExecutor.Lease stale = executor.claim("old").orElseThrow();

        expireLease(id);
        // expired but not yet replaced: still no writes, no renewal
        assertThatThrownBy(() -> executor.fenced(stale, row -> "write")).isInstanceOf(LeaseLostException.class);
        assertThat(executor.heartbeat(stale)).isZero();

        ProductionExecutor.Lease fresh = executor.claim("new").orElseThrow();
        assertThat(fresh.epoch()).isEqualTo(stale.epoch() + 1);
        assertThatThrownBy(() -> executor.fenced(stale, row -> "write")).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> executor.drive(stale)).isInstanceOf(LeaseLostException.class);
        assertThat(stepStatuses(id)).containsOnly("PENDING");

        executor.drive(fresh);
        assertThat(status(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void workerThatLostItsLeaseDuringAStepCannotRecordTheResult() throws Exception {
        long id = newRequest();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        work.hooks = Map.of(ProductionStage.SUBTITLE, context -> {
            if (context.attempt() == 1) {
                inside.countDown();
                await(resume);
            }
        });
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> old = thread.submit(() -> executor.runOnce("old"));
            assertThat(inside.await(30, TimeUnit.SECONDS)).isTrue();
            expireLease(id);
            // the replacement treats the interrupted local step as a failed attempt and runs it again
            assertThat(drain("new")).isEqualTo(1);
            assertThat(status(id)).isEqualTo("SUCCEEDED");
            resume.countDown();
            assertThat(old.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            thread.shutdownNow();
        }
        Map<String, Object> subtitle = step(id, ProductionStage.SUBTITLE);
        assertThat(subtitle.get("result_ref")).isEqualTo("local:SUBTITLE:2");
        assertThat(subtitle.get("attempts")).isEqualTo(2);
        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertLeaseReleased(id);
        assertThat(cost.dispatches.get()).isEqualTo(EFFECT_STAGES);
    }

    // ------------------------------------------------------------------ retries

    @Test
    void localFailureIsRetriedWithBackoffAndCanSucceed() {
        long id = newRequest();
        work.fail(ProductionStage.SUBTITLE, new StepFailure("SUBTITLE_TEMP", true, "temporary"));

        drain("w1");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertLeaseReleased(id);
        Map<String, Object> subtitle = step(id, ProductionStage.SUBTITLE);
        assertThat(subtitle.get("status")).isEqualTo("RETRY_WAIT");
        assertThat(subtitle.get("last_error_code")).isEqualTo("SUBTITLE_TEMP");
        assertThat(jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), retry_at) FROM production_step
                WHERE production_request_id = ? AND stage = 'SUBTITLE'
                """, Long.class, id)).isBetween(1L, 1_000_000L);
        assertThat(executor.runOnce("w1")).as("not due yet").isFalse();

        makeDue(id);
        drain("w1");
        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(step(id, ProductionStage.SUBTITLE).get("attempts")).isEqualTo(2);
        assertThat(step(id, ProductionStage.SUBTITLE).get("result_ref")).isEqualTo("local:SUBTITLE:2");
    }

    @Test
    void localFailureStopsAfterThreeAttemptsAndFailsTheRun() {
        long id = newRequest();
        work.fail(ProductionStage.RENDER, new IllegalStateException("1"), new IllegalStateException("2"),
                new IllegalStateException("3"), new IllegalStateException("never reached"));

        drain("w1");
        makeDue(id);
        drain("w1");
        makeDue(id);
        drain("w1");

        assertThat(status(id)).isEqualTo("FAILED");
        Map<String, Object> render = step(id, ProductionStage.RENDER);
        assertThat(render.get("status")).isEqualTo("FAILED");
        assertThat(render.get("attempts")).isEqualTo(3);
        assertThat(render.get("last_error_code")).isEqualTo("LOCAL_ERROR");
        assertThat(work.failures.get(ProductionStage.RENDER)).hasSize(1);
        assertThat(step(id, ProductionStage.METADATA).get("status")).isEqualTo("PENDING");
        assertRunFinished(id, "FAILED");
        assertLeaseReleased(id);
    }

    @Test
    void nonRetryableLocalFailureFailsImmediately() {
        long id = newRequest();
        work.fail(ProductionStage.SUBTITLE, new StepFailure("SUBTITLE_INVALID", false, "bad input"));

        drain("w1");

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(step(id, ProductionStage.SUBTITLE).get("attempts")).isEqualTo(1);
        assertRunFinished(id, "FAILED");
    }

    @Test
    void selectionFailureRollsBackTheLinkAndRetrySucceeds() {
        long id = newRequest();
        selection.failAfterWrites.set(1);

        drain("w1");
        Map<String, Object> pending = request(id);
        assertThat(pending.get("content_id")).isNull();
        assertThat(pending.get("run_id")).isNull();
        long channel = ((Number) pending.get("channel_id")).longValue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isZero();
        assertThat(step(id, ProductionStage.SELECT).get("status")).isEqualTo("RETRY_WAIT");

        makeDue(id);
        drain("w1");
        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content WHERE channel_id = ?", Integer.class, channel))
                .isEqualTo(1);
        assertThat(step(id, ProductionStage.SELECT).get("attempts")).isEqualTo(2);
        assertRunFinished(id, "SUCCEEDED");
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelBeforeStartCancelsEveryStepWithoutEffects() {
        long id = newRequest();
        cancel(id);

        drain("w1");

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(stepStatuses(id)).containsOnly("CANCELLED");
        assertThat(cost.presentations.get()).isZero();
        assertThat(request(id).get("run_id")).isNull();
        assertLeaseReleased(id);
    }

    @Test
    void cancelDuringRetryWaitIsHonouredWithoutWaiting() {
        long id = newRequest();
        work.fail(ProductionStage.RENDER, new IllegalStateException("temporary"));
        drain("w1");
        assertThat(step(id, ProductionStage.RENDER).get("status")).isEqualTo("RETRY_WAIT");

        cancel(id);
        drain("w1");

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(step(id, ProductionStage.RENDER).get("status")).isEqualTo("CANCELLED");
        assertThat(step(id, ProductionStage.SUBTITLE).get("status")).isEqualTo("SUCCEEDED");
        assertRunFinished(id, "CANCELLED");
    }

    @Test
    void cancelWhileALocalStepRunsKeepsItsResultAndStopsBeforeTheNextEffect() throws Exception {
        long id = newRequest();
        work.hooks = Map.of(ProductionStage.RENDER, context -> cancel(context.requestId()));

        drain("w1");

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(step(id, ProductionStage.RENDER).get("status")).isEqualTo("SUCCEEDED");
        assertThat(step(id, ProductionStage.METADATA).get("status")).isEqualTo("CANCELLED");
        assertThat(step(id, ProductionStage.QC).get("status")).isEqualTo("CANCELLED");
        assertThat(cost.dispatches.get()).isEqualTo(6); // DISCOVER..TTS, nothing after cancel
        assertRunFinished(id, "CANCELLED");
    }

    @Test
    void cancelBeforeSelectionLinkCreatesNoContent() {
        long id = newRequest();
        work.hooks = Map.of(ProductionStage.SELECT, context -> cancel(context.requestId()));

        drain("w1");

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(request(id).get("run_id")).isNull();
        assertThat(selection.links.get()).isZero();
        assertThat(step(id, ProductionStage.DISCOVER).get("status")).isEqualTo("SUCCEEDED");
        assertThat(step(id, ProductionStage.SELECT).get("status")).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ unknown effects and recovery

    @Test
    void unknownEffectIsIsolatedAndNeverRedispatched() {
        long id = newRequest();
        cost.behavior = stage -> stage == ProductionStage.SCRIPT ? Behavior.UNKNOWN : Behavior.ADOPT;

        drain("w1");

        assertThat(status(id)).isEqualTo("RECOVERY_REQUIRED");
        assertLeaseReleased(id);
        assertThat(step(id, ProductionStage.SCRIPT).get("status")).isEqualTo("RECOVERY_REQUIRED");
        assertThat(step(id, ProductionStage.SCRIPT).get("last_error_code")).isEqualTo("EFFECT_RECOVERY_REQUIRED");
        assertThat(runStatus(id)).as("run is not closed while recovery is pending").isEqualTo("RUNNING");
        assertThat(content(id).get("status")).isEqualTo("PRODUCING");
        assertThat(executor.runOnce("w1")).as("recheck not due").isFalse();

        makeDue(id);
        drain("w1");
        assertThat(status(id)).as("still unknown after a recheck").isEqualTo("RECOVERY_REQUIRED");
        assertThat(cost.dispatches.get()).isEqualTo(3); // DISCOVER, RESEARCH, SCRIPT once

        ExecutionFakes.Op script = cost.forStage(id, ProductionStage.SCRIPT);
        cost.reconcile(script.id, "lookup-1", new ProductionCostPort.Evidence(
                ProductionCostPort.Outcome.RESULT_ADOPTED, "ext-1", "KRW", java.math.BigDecimal.ONE, null, null, "q"));
        makeDue(id);
        drain("w1");

        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(step(id, ProductionStage.SCRIPT).get("attempts")).as("recovery is not an attempt").isEqualTo(1);
        assertThat(cost.dispatches.get()).isEqualTo(EFFECT_STAGES);
        assertThat(script.applied).isTrue();
        assertRunFinished(id, "SUCCEEDED");
    }

    @Test
    void portExceptionAfterDispatchIsUnknownNotRetried() {
        long id = newRequest();
        cost.behavior = stage -> stage == ProductionStage.IMAGE ? Behavior.THROW_AFTER_DISPATCH : Behavior.ADOPT;

        drain("w1");

        assertThat(status(id)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(step(id, ProductionStage.IMAGE).get("last_error_code")).isEqualTo("EFFECT_UNKNOWN");
        assertThat(step(id, ProductionStage.IMAGE).get("attempts")).isEqualTo(1);
        makeDue(id);
        drain("w1");
        assertThat(cost.dispatches.get()).isEqualTo(5); // DISCOVER..IMAGE, IMAGE once
        assertThat(status(id)).isEqualTo("RECOVERY_REQUIRED");
    }

    @Test
    void cancelWaitsForAnUnknownEffectAndFinishesAfterReconciliation() {
        long id = newRequest();
        cost.behavior = stage -> stage == ProductionStage.TTS ? Behavior.WAITING : Behavior.ADOPT;
        drain("w1");
        assertThat(step(id, ProductionStage.TTS).get("last_error_code")).isEqualTo("EFFECT_WAITING");

        cancel(id);
        assertThat(executor.runOnce("w1")).as("cancel does not skip the recovery recheck").isFalse();
        makeDue(id);
        drain("w1");
        assertThat(status(id)).as("cancel cannot finish while the effect is unknown").isEqualTo("RECOVERY_REQUIRED");
        assertThat(runStatus(id)).isEqualTo("RUNNING");

        cost.reconcile(cost.forStage(id, ProductionStage.TTS).id, "lookup", new ProductionCostPort.Evidence(
                ProductionCostPort.Outcome.NOT_EXECUTED, null, null, null, null, null, "provider says not run"));
        makeDue(id);
        drain("w1");

        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(step(id, ProductionStage.TTS).get("status")).isEqualTo("CANCELLED");
        assertThat(step(id, ProductionStage.SUBTITLE).get("status")).isEqualTo("CANCELLED");
        assertThat(cost.dispatches.get()).isEqualTo(6);
        assertRunFinished(id, "CANCELLED");
    }

    @Test
    void executedFailureAndRejectionFailTheStepWithoutRetry() {
        long failed = newRequest();
        cost.behavior = stage -> stage == ProductionStage.RESEARCH ? Behavior.EXECUTED_FAILED : Behavior.ADOPT;
        drain("w1");
        assertThat(status(failed)).isEqualTo("FAILED");
        assertThat(step(failed, ProductionStage.RESEARCH).get("last_error_code")).isEqualTo("EFFECT_EXECUTED_FAILED");
        assertThat(step(failed, ProductionStage.RESEARCH).get("attempts")).isEqualTo(1);
        assertRunFinished(failed, "FAILED");

        cost.reset();
        long rejected = newRequest();
        cost.behavior = stage -> stage == ProductionStage.DISCOVER ? Behavior.REJECT : Behavior.ADOPT;
        drain("w1");
        assertThat(status(rejected)).isEqualTo("FAILED");
        assertThat(step(rejected, ProductionStage.DISCOVER).get("last_error_code")).isEqualTo("EFFECT_REJECTED");
        assertThat(request(rejected).get("run_id")).isNull();
        assertThat(cost.dispatches.get()).isZero();
    }

    // ------------------------------------------------------------------ restart recovery

    @Test
    void restartAfterDispatchRecoversWithTheSameEffectAndNoSecondDispatch() {
        long id = newRequest();
        cost.afterDispatch = op -> {
            if (op.stage == ProductionStage.SCENES) {
                throw new Crash("after dispatch");
            }
        };
        assertThatThrownBy(() -> executor.runOnce("before-restart")).isInstanceOf(Crash.class);
        assertThat(step(id, ProductionStage.SCENES).get("status")).isEqualTo("RUNNING");
        ExecutionFakes.Op scenes = cost.forStage(id, ProductionStage.SCENES);
        cost.reconcile(scenes.id, "lookup", new ProductionCostPort.Evidence(
                ProductionCostPort.Outcome.RESULT_ADOPTED, "ext", "KRW", java.math.BigDecimal.ONE, null, null, "q"));
        cost.afterDispatch = op -> {};

        assertThat(executor.runOnce("after-restart")).as("old lease still valid").isFalse();
        expireLease(id);
        drain("after-restart");

        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(cost.dispatches.get()).isEqualTo(EFFECT_STAGES);
        assertThat(step(id, ProductionStage.SCENES).get("attempts")).isEqualTo(1);
        assertThat(request(id).get("lease_epoch")).isEqualTo(2L);
    }

    @Test
    void restartBeforeReceiptCommitReappliesTheStoredReceiptOnce() {
        long id = newRequest();
        long[] crashOn = { -1 };
        cost.beforeApply = operationId -> {
            if (crashOn[0] < 0 && cost.byId(operationId).stage == ProductionStage.METADATA) {
                crashOn[0] = operationId;
                throw new Crash("inside receipt transaction");
            }
        };
        assertThatThrownBy(() -> executor.runOnce("before-restart")).isInstanceOf(Crash.class);
        ExecutionFakes.Op metadata = cost.byId(crashOn[0]);
        assertThat(metadata.applied).as("rolled back with the step update").isFalse();
        assertThat(step(id, ProductionStage.METADATA).get("status")).isEqualTo("RUNNING");

        expireLease(id);
        drain("after-restart");

        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(metadata.applied).isTrue();
        assertThat(cost.applications.get()).isEqualTo(EFFECT_STAGES);
        assertThat(cost.dispatches.get()).isEqualTo(EFFECT_STAGES);
    }

    @Test
    void restartDuringALocalStepRetriesItWithinTheAttemptLimit() {
        long id = newRequest();
        work.hooks = Map.of(ProductionStage.RENDER, context -> {
            if (context.attempt() == 1) {
                throw new Crash("inside local step");
            }
        });
        assertThatThrownBy(() -> executor.runOnce("before-restart")).isInstanceOf(Crash.class);
        expireLease(id);
        drain("after-restart");

        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(step(id, ProductionStage.RENDER).get("attempts")).isEqualTo(2);
        assertThat(step(id, ProductionStage.RENDER).get("result_ref")).isEqualTo("local:RENDER:2");
    }

    @Test
    void repeatedRestartsInsideALocalStepEndAtThreeAttempts() {
        long id = newRequest();
        work.hooks = Map.of(ProductionStage.SUBTITLE, context -> {
            throw new Crash("always");
        });
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> executor.runOnce("crashing")).isInstanceOf(Crash.class);
            expireLease(id);
        }
        drain("after-restart");

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(step(id, ProductionStage.SUBTITLE).get("attempts")).isEqualTo(3);
        assertThat(step(id, ProductionStage.SUBTITLE).get("last_error_code")).isEqualTo("INTERRUPTED");
        assertRunFinished(id, "FAILED");
    }

    // ------------------------------------------------------------------ completion gate

    @Test
    void completionWaitsForCostStateAndRunStaysOpen() {
        long id = newRequest();
        cost.unresolvedOverride = true;
        drain("w1");
        assertThat(status(id)).isEqualTo("RECOVERY_REQUIRED");
        assertThat(stepStatuses(id)).containsOnly("SUCCEEDED");
        assertThat(runStatus(id)).isEqualTo("RUNNING");
        assertLeaseReleased(id);
        assertThat(executor.runOnce("w1")).isFalse();

        cost.unresolvedOverride = false;
        cost.readFailure = new IllegalStateException("cost store unavailable");
        makeDue(id);
        drain("w1");
        assertThat(status(id)).isEqualTo("RECOVERY_REQUIRED");

        cost.readFailure = null;
        cost.spendingBlocked = true;
        makeDue(id);
        drain("w1");
        assertThat(status(id)).as("spending block prevents normal completion").isEqualTo("RECOVERY_REQUIRED");

        cost.spendingBlocked = false;
        makeDue(id);
        drain("w1");
        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM production_step WHERE production_request_id = ? AND retry_at IS NOT NULL
                """, Integer.class, id)).isZero();
        assertRunFinished(id, "SUCCEEDED");
    }

    @Test
    void spendingBlockStillAllowsFailureToClose() {
        long id = newRequest();
        cost.spendingBlocked = true;
        work.fail(ProductionStage.SUBTITLE, new StepFailure("SUBTITLE_INVALID", false, "bad"));
        drain("w1");
        assertThat(status(id)).isEqualTo("FAILED");
        assertRunFinished(id, "FAILED");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
