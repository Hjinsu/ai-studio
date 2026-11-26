package com.aistudio.production.execution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aistudio.content.ContentFixtures;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.api.ErrorCode;

/**
 * Test doubles of the two common ports and of stage work. The cost double follows the port contract the executor
 * relies on: one dispatch per effect key, re-presentation returns the stored state, a different hash for the same
 * key is rejected, and receipt application joins the caller's transaction (applied only on commit).
 */
final class ExecutionFakes {
    private ExecutionFakes() {}

    /** Simulates the JVM stopping at an arbitrary point. Not a RuntimeException, so nothing handles it. */
    static final class Crash extends Error {
        Crash(String where) {
            super("simulated crash: " + where);
        }
    }

    enum Behavior { ADOPT, EXECUTED_FAILED, NOT_EXECUTED, UNKNOWN, WAITING, REJECT, THROW_AFTER_DISPATCH }

    static final class Op {
        final long id;
        final long requestId;
        final long stepId;
        final ProductionStage stage;
        final String hash;
        volatile boolean unknown;
        volatile ProductionCostPort.Receipt receipt;
        volatile boolean applied;

        Op(long id, long requestId, long stepId, ProductionStage stage, String hash) {
            this.id = id;
            this.requestId = requestId;
            this.stepId = stepId;
            this.stage = stage;
            this.hash = hash;
        }
    }

    static final class FakeCost implements ProductionCostPort {
        private final JdbcTemplate jdbc;
        private final AtomicLong ids = new AtomicLong(1000);
        final Map<String, Op> ops = new ConcurrentHashMap<>();
        final AtomicInteger presentations = new AtomicInteger();
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicInteger applications = new AtomicInteger();
        volatile Function<ProductionStage, Behavior> behavior = stage -> Behavior.ADOPT;
        volatile Consumer<Op> afterDispatch = op -> {};
        volatile Consumer<Long> beforeApply = op -> {};
        volatile boolean unresolvedOverride;
        volatile boolean spendingBlocked;
        volatile RuntimeException readFailure;

        FakeCost(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void reset() {
            ops.clear();
            presentations.set(0);
            dispatches.set(0);
            applications.set(0);
            behavior = stage -> Behavior.ADOPT;
            afterDispatch = op -> {};
            beforeApply = op -> {};
            unresolvedOverride = false;
            spendingBlocked = false;
            readFailure = null;
        }

        @Override
        public EffectResult executeEffect(StepOwner owner, String effectKey, String requestHash, PricedRequest request) {
            presentations.incrementAndGet();
            if (!ownerIsCurrent(owner)) {
                return new EffectResult(Disposition.REJECTED, null);
            }
            Op existing = ops.get(effectKey);
            if (existing != null) {
                if (!existing.hash.equals(requestHash) || existing.stepId != owner.stepId()) {
                    return new EffectResult(Disposition.REJECTED, null);
                }
                if (existing.receipt != null) {
                    return new EffectResult(Disposition.RECEIPT, existing.receipt);
                }
                return new EffectResult(Disposition.RECOVERY_REQUIRED, null);
            }
            Boolean cancelled = jdbc.queryForObject(
                    "SELECT cancel_requested_at IS NOT NULL FROM production_request WHERE id = ?", Boolean.class,
                    owner.requestId());
            if (Boolean.TRUE.equals(cancelled)) {
                return new EffectResult(Disposition.REJECTED, null); // no new dispatch after cancel
            }
            ProductionStage stage = ProductionStage.valueOf(jdbc.queryForObject(
                    "SELECT stage FROM production_step WHERE id = ?", String.class, owner.stepId()));
            Behavior chosen = behavior.apply(stage);
            if (chosen == Behavior.REJECT) {
                return new EffectResult(Disposition.REJECTED, null);
            }
            Op op = new Op(ids.incrementAndGet(), owner.requestId(), owner.stepId(), stage, requestHash);
            ops.put(effectKey, op);
            dispatches.incrementAndGet();
            afterDispatch.accept(op);
            switch (chosen) {
                case ADOPT -> settle(op, Outcome.RESULT_ADOPTED);
                case EXECUTED_FAILED -> settle(op, Outcome.EXECUTED_FAILED);
                case NOT_EXECUTED -> settle(op, Outcome.NOT_EXECUTED);
                case UNKNOWN -> op.unknown = true;
                case WAITING -> {
                    op.unknown = true;
                    return new EffectResult(Disposition.WAITING, null);
                }
                case THROW_AFTER_DISPATCH -> {
                    op.unknown = true;
                    throw new IllegalStateException("simulated timeout after dispatch");
                }
                default -> throw new IllegalStateException();
            }
            return op.receipt != null ? new EffectResult(Disposition.RECEIPT, op.receipt)
                    : new EffectResult(Disposition.RECOVERY_REQUIRED, null);
        }

        private Receipt settle(Op op, Outcome outcome) {
            op.unknown = false;
            op.receipt = new Receipt(op.id, outcome, outcome == Outcome.RESULT_ADOPTED
                    ? "fake-result:" + op.stage.name() : null, null, op.id + 50_000);
            return op.receipt;
        }

        private boolean ownerIsCurrent(StepOwner owner) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT lease_owner = ? AND lease_epoch = ? AND lease_until > UTC_TIMESTAMP(6)
                    FROM production_request WHERE id = ?
                    """, Boolean.class, owner.leaseOwner(), owner.leaseEpoch(), owner.requestId()));
        }

        @Override
        public CostState readCostState(long requestId) {
            if (readFailure != null) {
                throw readFailure;
            }
            boolean unresolved = unresolvedOverride;
            List<Receipt> unapplied = new ArrayList<>();
            for (Op op : ops.values()) {
                if (op.requestId != requestId) {
                    continue;
                }
                unresolved |= op.unknown;
                if (op.receipt != null && !op.applied) {
                    unapplied.add(op.receipt);
                }
            }
            return new CostState(unresolved, spendingBlocked, unapplied);
        }

        @Override
        public void markReceiptApplied(StepOwner owner, long operationId) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("markReceiptApplied must join the executor transaction");
            }
            if (!ownerIsCurrent(owner)) {
                throw new IllegalStateException("stale owner");
            }
            beforeApply.accept(operationId);
            Op op = byId(operationId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (!op.applied) {
                        op.applied = true;
                        applications.incrementAndGet();
                    }
                }
            });
        }

        @Override
        public Receipt reconcile(long operationId, String evidenceKey, Evidence evidence) {
            Op op = byId(operationId);
            return op.receipt != null ? op.receipt : settle(op, evidence.outcome());
        }

        Op byId(long operationId) {
            return ops.values().stream().filter(o -> o.id == operationId).findFirst().orElseThrow();
        }

        Op forStage(long requestId, ProductionStage stage) {
            return ops.values().stream().filter(o -> o.requestId == requestId && o.stage == stage).findFirst()
                    .orElseThrow();
        }
    }

    static final class FakeSelection implements ProductionSelectionPort {
        private final JdbcTemplate jdbc;
        private final ContentFixtures fixtures;
        final AtomicInteger failAfterWrites = new AtomicInteger();
        final AtomicInteger links = new AtomicInteger();

        FakeSelection(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            this.fixtures = new ContentFixtures(jdbc);
        }

        void reset() {
            failAfterWrites.set(0);
            links.set(0);
        }

        @Override
        public Selected link(ProductionCostPort.StepOwner owner, Selection selection) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("link must join the executor transaction");
            }
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT channel_id, channel_config_id, content_id, run_id, selection_hash
                    FROM production_request WHERE id = ? FOR UPDATE
                    """, owner.requestId());
            if (row.get("run_id") != null) {
                if (!selection.fingerprint().equals(row.get("selection_hash"))) {
                    throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
                }
                long contentId = ((Number) row.get("content_id")).longValue();
                long runId = ((Number) row.get("run_id")).longValue();
                Map<String, Object> content = jdbc.queryForMap(
                        "SELECT topic_id, current_revision_id FROM content WHERE id = ?", contentId);
                return new Selected(((Number) content.get("topic_id")).longValue(), contentId,
                        ((Number) content.get("current_revision_id")).longValue(), runId);
            }
            long channelId = ((Number) row.get("channel_id")).longValue();
            long configId = ((Number) row.get("channel_config_id")).longValue();
            long topicId = fixtures.topic(channelId, selection.fingerprint());
            ContentFixtures.Created created = fixtures.content(channelId, topicId, configId);
            long runId = fixtures.activate(created.contentId(), created.revisionId(), 1);
            jdbc.update("UPDATE production_request SET content_id = ?, run_id = ?, selection_hash = ? WHERE id = ?",
                    created.contentId(), runId, selection.fingerprint(), owner.requestId());
            if (failAfterWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated failure after selection writes");
            }
            links.incrementAndGet();
            return new Selected(topicId, created.contentId(), created.revisionId(), runId);
        }
    }

    /** FAKE stage work with injectable local failures and hooks. Local results carry the attempt number. */
    static final class ScriptedWork extends FakeStageWork {
        final Map<ProductionStage, Queue<RuntimeException>> failures = new ConcurrentHashMap<>();
        volatile Map<ProductionStage, Consumer<StepContext>> hooks = Map.of();

        ScriptedWork() {
            super(BigDecimal.ONE);
        }

        void reset() {
            failures.clear();
            hooks = Map.of();
        }

        void fail(ProductionStage stage, RuntimeException... errors) {
            failures.computeIfAbsent(stage, s -> new ConcurrentLinkedQueue<>()).addAll(List.of(errors));
        }

        @Override
        public String runLocal(StepContext context) {
            hooks.getOrDefault(context.stage(), c -> {}).accept(context);
            RuntimeException failure = failures.getOrDefault(context.stage(), new ConcurrentLinkedQueue<>()).poll();
            if (failure != null) {
                throw failure;
            }
            return "local:" + context.stage().name() + ":" + context.attempt();
        }

        @Override
        public ProductionSelectionPort.Selection select(StepContext context) {
            hooks.getOrDefault(context.stage(), c -> {}).accept(context);
            return super.select(context);
        }
    }
}
