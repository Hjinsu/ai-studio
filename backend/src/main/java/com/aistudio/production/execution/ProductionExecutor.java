package com.aistudio.production.execution;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.ProductionCostPort;
import com.aistudio.production.contract.ProductionCostPort.CostState;
import com.aistudio.production.contract.ProductionCostPort.EffectResult;
import com.aistudio.production.contract.ProductionCostPort.PricedRequest;
import com.aistudio.production.contract.ProductionCostPort.Receipt;
import com.aistudio.production.contract.ProductionCostPort.StepOwner;
import com.aistudio.production.contract.ProductionSelectionPort;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.production.contract.StepStatus;
import com.aistudio.production.execution.ExecutionMapper.RequestRow;
import com.aistudio.production.execution.ExecutionMapper.StepRow;
import com.aistudio.shared.api.ApiException;
import com.aistudio.shared.db.BudgetControlLock;
import com.aistudio.shared.db.LockRetryingTransactions;

/**
 * Fixed 11-stage executor for one production request at a time per worker (V2-01).
 *
 * <ul>
 * <li>Claim takes budget_control exclusively, counts valid leases on the DB UTC clock (at most
 * {@code maxLeases}), then locks the request and bumps its monotonic lease epoch.</li>
 * <li>Every later state change locks the request and checks owner, epoch and expiry first (fencing). An expired
 * or replaced worker cannot change anything.</li>
 * <li>Network and file work runs outside transactions. Effects are only requested through
 * {@link ProductionCostPort#executeEffect}; an exception or missing answer is an unknown outcome and parks the
 * step for recovery. It is never retried as a new attempt.</li>
 * <li>Only local effect-free work is retried, with backoff, up to three attempts in total.</li>
 * <li>The request and its V6 run only finish when the cost port reports nothing unresolved or unapplied.</li>
 * </ul>
 */
public class ProductionExecutor implements AutoCloseable {
    record Lease(long requestId, String owner, long epoch) {
        StepOwner stepOwner(long stepId) {
            return new StepOwner(requestId, stepId, owner, epoch);
        }
    }

    /** Upper bound on actions per claim; a correct run of 11 stages with retries needs far fewer. */
    static final int MAX_ACTIONS_PER_CLAIM = 64;
    private static final Logger log = LoggerFactory.getLogger(ProductionExecutor.class);

    private final ExecutionMapper mapper;
    private final BudgetControlLock control;
    private final LockRetryingTransactions transactions;
    private final ProductionCostPort cost;
    private final ProductionSelectionPort selection;
    private final StageWork work;
    private final ExecutionProperties properties;
    private final ScheduledExecutorService heartbeats;

    public ProductionExecutor(ExecutionMapper mapper, BudgetControlLock control, LockRetryingTransactions transactions,
            ProductionCostPort cost, ProductionSelectionPort selection, StageWork work,
            ExecutionProperties properties) {
        StageWork.validate(work);
        this.mapper = mapper;
        this.control = control;
        this.transactions = transactions;
        this.cost = cost;
        this.selection = selection;
        this.work = work;
        this.properties = properties;
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "production-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        this.heartbeats = scheduler;
    }

    /** Claims one due request and drives it until it finishes, waits or loses its lease. */
    public boolean runOnce(String owner) {
        Optional<Lease> claimed = claim(owner);
        if (claimed.isEmpty()) {
            return false;
        }
        Lease lease = claimed.get();
        try {
            drive(lease);
        } catch (LeaseLostException lost) {
            log.warn("Production request {} lease epoch {} lost; stopping without changes", lease.requestId(),
                    lease.epoch());
        } catch (RuntimeException e) {
            log.error("Production request {} stopped by an unexpected error; lease left to expire",
                    lease.requestId(), e);
        }
        return true;
    }

    Optional<Lease> claim(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("Invalid lease owner");
        }
        return transactions.execute(() -> {
            control.exclusive();
            if (mapper.countValidLeases() >= properties.maxLeases()) {
                return Optional.<Lease>empty();
            }
            Long id = mapper.nextClaimable();
            if (id == null || mapper.claim(id, owner, leaseMicros()) != 1) {
                return Optional.<Lease>empty();
            }
            return Optional.of(new Lease(id, owner, mapper.read(id).leaseEpoch()));
        });
    }

    void drive(Lease lease) {
        for (int i = 0; i < MAX_ACTIONS_PER_CLAIM; i++) {
            Snapshot snapshot = fenced(lease, request -> new Snapshot(request, mapper.steps(request.id())));
            if (isTerminal(snapshot.request().status())) {
                return;
            }
            StepPlanner.Action action = StepPlanner.next(snapshot.steps(), snapshot.request().cancelRequested(),
                    work::kind);
            switch (action) {
                case StepPlanner.Recover recover -> {
                    if (!presentEffect(lease, snapshot.request(), recover.step(), 0)) {
                        return;
                    }
                }
                case StepPlanner.Execute execute -> {
                    if (!execute(lease, snapshot.request(), execute.step())) {
                        return;
                    }
                }
                case StepPlanner.Fail fail -> fenced(lease, request ->
                        mapper.endStep(request.id(), fail.step().id(), StepStatus.FAILED, fail.code()));
                case StepPlanner.Finish finish -> {
                    finish(lease, snapshot.steps(), finish.status());
                    return;
                }
                case StepPlanner.Release release -> {
                    release(lease);
                    return;
                }
            }
        }
        log.error("Production request {} exceeded {} actions in one claim; releasing", lease.requestId(),
                MAX_ACTIONS_PER_CLAIM);
        release(lease);
    }

    /** @return true to continue driving the request in this claim */
    private boolean execute(Lease lease, RequestRow request, StepRow step) {
        return switch (work.kind(step.stage())) {
            case EFFECT -> presentEffect(lease, request, step, 1);
            case LOCAL -> runLocal(lease, request, step);
            case SELECT -> runSelect(lease, request, step);
        };
    }

    // ------------------------------------------------------------------ local work

    private boolean runLocal(Lease lease, RequestRow request, StepRow step) {
        int attempt = start(lease, step, 1);
        StageWork.StepContext context = context(request, step, attempt);
        String result;
        try {
            result = requireResult(withHeartbeat(lease, () -> work.runLocal(context)));
        } catch (RuntimeException e) {
            return localFailure(lease, step, attempt, e);
        }
        fenced(lease, locked -> mapper.succeedStep(locked.id(), step.id(), result));
        return true;
    }

    private boolean runSelect(Lease lease, RequestRow request, StepRow step) {
        int attempt = start(lease, step, 1);
        StageWork.StepContext context = context(request, step, attempt);
        try {
            ProductionSelectionPort.Selection chosen = withHeartbeat(lease, () -> work.select(context));
            fenced(lease, locked -> {
                if (locked.cancelRequested()) {
                    return null; // do not link after cancel; the planner cancels the request
                }
                ProductionSelectionPort.Selected selected = selection.link(lease.stepOwner(step.id()), chosen);
                mapper.startRun(selected.contentId(), selected.runId());
                return mapper.succeedStep(locked.id(), step.id(), "run:" + selected.runId());
            });
            return true;
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            return localFailure(lease, step, attempt, e);
        }
    }

    private boolean localFailure(Lease lease, StepRow step, int attempt, RuntimeException e) {
        if (e instanceof LeaseLostException lost) {
            throw lost;
        }
        String code;
        boolean retryable;
        if (e instanceof StepFailure failure) {
            code = failure.code();
            retryable = failure.retryable();
        } else if (e instanceof ApiException api) {
            code = api.errorCode().name();
            retryable = false;
        } else {
            code = "LOCAL_ERROR";
            retryable = true;
        }
        log.warn("Production step {} {} attempt {} failed: {}", step.id(), step.stage(), attempt, code, e);
        if (retryable && attempt < ExecutionProperties.MAX_ATTEMPTS) {
            long delay = RetryBackoff.delayMillis(attempt, properties.retryBase().toMillis(),
                    properties.retryCap().toMillis(), bound -> ThreadLocalRandom.current().nextLong(bound));
            fenced(lease, locked -> mapper.waitStep(locked.id(), step.id(), StepStatus.RETRY_WAIT, code,
                    delay * 1000));
            return true; // the planner releases the lease while the retry is not due
        }
        fenced(lease, locked -> mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, code));
        return true;
    }

    // ------------------------------------------------------------------ effects

    /**
     * Presents the step's single logical effect to the cost port. A first presentation counts an attempt; a
     * recovery presentation reuses the same key and hash and does not.
     */
    private boolean presentEffect(Lease lease, RequestRow request, StepRow step, int attemptIncrement) {
        int attempt = start(lease, step, attemptIncrement);
        StageWork.StepContext context = context(request, step, attempt);
        PricedRequest priced;
        String effectKey;
        String requestHash;
        try {
            priced = work.price(context);
            effectKey = EffectKey.forInput(request.id(), step.stage(), step.targetKey(), step.inputGeneration());
            requestHash = EffectRequestHash.of(step.stage(), step.targetKey(), step.inputGeneration(), priced);
        } catch (RuntimeException e) {
            if (attemptIncrement == 0) {
                // The port may already hold this effect; without its identity we can only wait for recovery.
                log.warn("Production step {} {} cannot be re-priced", step.id(), step.stage(), e);
                park(lease, step, "EFFECT_PRICE_UNAVAILABLE");
                return false;
            }
            return localFailure(lease, step, attempt, e); // nothing was presented to the port yet
        }
        EffectResult result;
        try {
            result = withHeartbeat(lease, () -> cost.executeEffect(lease.stepOwner(step.id()), effectKey, requestHash,
                    priced));
        } catch (RuntimeException e) {
            log.warn("Production step {} {} effect outcome unknown", step.id(), step.stage(), e);
            result = null;
        }
        if (result == null || result.disposition() == null) {
            park(lease, step, "EFFECT_UNKNOWN");
            return false;
        }
        switch (result.disposition()) {
            case RECEIPT -> {
                return applyReceipt(lease, step, result.receipt());
            }
            case REJECTED -> {
                fenced(lease, locked -> {
                    resumeRequest(locked);
                    return mapper.endStep(locked.id(), step.id(),
                            locked.cancelRequested() ? StepStatus.CANCELLED : StepStatus.FAILED, "EFFECT_REJECTED");
                });
                return true;
            }
            case WAITING -> {
                park(lease, step, "EFFECT_WAITING");
                return false;
            }
            default -> {
                park(lease, step, "EFFECT_RECOVERY_REQUIRED");
                return false;
            }
        }
    }

    /** Applies a final receipt and the step result in one transaction, joined by the cost port. */
    private boolean applyReceipt(Lease lease, StepRow step, Receipt receipt) {
        if (receipt == null || receipt.outcome() == null) {
            park(lease, step, "RECEIPT_INVALID");
            return false;
        }
        try {
            fenced(lease, locked -> {
                cost.markReceiptApplied(lease.stepOwner(step.id()), receipt.operationId());
                resumeRequest(locked);
                return switch (receipt.outcome()) {
                    case RESULT_ADOPTED -> mapper.succeedStep(locked.id(), step.id(), receipt.resultRef() != null
                            ? receipt.resultRef() : "operation:" + receipt.operationId());
                    case EXECUTED_FAILED -> mapper.endStep(locked.id(), step.id(), StepStatus.FAILED,
                            "EFFECT_EXECUTED_FAILED");
                    case NOT_EXECUTED -> mapper.endStep(locked.id(), step.id(),
                            locked.cancelRequested() ? StepStatus.CANCELLED : StepStatus.FAILED,
                            "EFFECT_NOT_EXECUTED");
                };
            });
            return true;
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            log.warn("Production step {} receipt {} not applied; recheck later", step.id(), receipt.operationId(), e);
            park(lease, step, "RECEIPT_APPLY_FAILED");
            return false;
        }
    }

    /** Effect outcome not final: keep the reservation, mark the request for recovery and release the lease. */
    private void park(Lease lease, StepRow step, String code) {
        fenced(lease, locked -> {
            mapper.waitStep(locked.id(), step.id(), StepStatus.RECOVERY_REQUIRED, code, recheckMicros());
            mapper.moveRequest(locked.id(), ProductionStatus.RUNNING, ProductionStatus.RECOVERY_REQUIRED);
            return mapper.release(locked.id(), lease.owner(), lease.epoch());
        });
    }

    private void resumeRequest(RequestRow locked) {
        mapper.moveRequest(locked.id(), ProductionStatus.RECOVERY_REQUIRED, ProductionStatus.RUNNING);
    }

    // ------------------------------------------------------------------ finish

    private void finish(Lease lease, List<StepRow> steps, ProductionStatus target) {
        String blocked;
        try {
            CostState state = cost.readCostState(lease.requestId());
            blocked = state == null ? "COST_STATE_UNAVAILABLE"
                    : state.unresolved() ? "COST_UNRESOLVED"
                    : !state.unappliedReceipts().isEmpty() ? "RECEIPT_UNAPPLIED"
                    : state.spendingBlocked() && target == ProductionStatus.SUCCEEDED ? "SPENDING_BLOCKED"
                    : null;
        } catch (RuntimeException e) {
            log.warn("Production request {} cost state unavailable", lease.requestId(), e);
            blocked = "COST_STATE_UNAVAILABLE";
        }
        if (blocked != null) {
            String reason = blocked;
            long anchor = anchorStep(steps);
            log.warn("Production request {} cannot finish as {}: {}", lease.requestId(), target, reason);
            fenced(lease, locked -> {
                mapper.scheduleRecheck(locked.id(), anchor, recheckMicros());
                mapper.moveRequest(locked.id(), ProductionStatus.RUNNING, ProductionStatus.RECOVERY_REQUIRED);
                return mapper.release(locked.id(), lease.owner(), lease.epoch());
            });
            return;
        }
        fenced(lease, locked -> {
            if (target == ProductionStatus.CANCELLED) {
                mapper.cancelOpenSteps(locked.id());
            }
            mapper.clearRechecks(locked.id());
            if (locked.runId() != null) {
                mapper.releaseActiveRun(locked.contentId(), locked.runId());
                mapper.finishRun(locked.contentId(), locked.runId(), target.name());
            }
            if (mapper.finishRequest(locked.id(), target) != 1) {
                throw new IllegalStateException("Production request " + locked.id() + " could not finish");
            }
            return null;
        });
        log.info("Production request {} finished as {}", lease.requestId(), target);
    }

    /** The last step that ran; it carries the recheck time of a request blocked on cost state. */
    private static long anchorStep(List<StepRow> steps) {
        long anchor = steps.get(0).id();
        for (StepRow step : steps) {
            if (step.status() != StepStatus.PENDING) {
                anchor = step.id();
            }
        }
        return anchor;
    }

    // ------------------------------------------------------------------ lease and transactions

    private int start(Lease lease, StepRow step, int attemptIncrement) {
        fenced(lease, locked -> {
            if (mapper.startStep(locked.id(), step.id(), attemptIncrement) != 1) {
                throw new IllegalStateException("Step " + step.id() + " cannot start");
            }
            return null;
        });
        return step.attempts() + attemptIncrement;
    }

    private void release(Lease lease) {
        transactions.execute(() -> mapper.release(lease.requestId(), lease.owner(), lease.epoch()));
    }

    /** Locks the request, verifies owner, epoch and expiry on the DB clock, renews the lease, then runs work. */
    <T> T fenced(Lease lease, Function<RequestRow, T> work) {
        return transactions.execute(() -> {
            RequestRow locked = mapper.lock(lease.requestId());
            if (locked == null || !lease.owner().equals(locked.leaseOwner()) || locked.leaseEpoch() != lease.epoch()
                    || !locked.leaseValid()
                    || mapper.heartbeat(locked.id(), lease.owner(), lease.epoch(), leaseMicros()) != 1) {
                throw new LeaseLostException(lease.requestId());
            }
            return work.apply(locked);
        });
    }

    /** Renews the lease periodically while work runs outside a transaction. */
    private <T> T withHeartbeat(Lease lease, Supplier<T> task) {
        AtomicBoolean lost = new AtomicBoolean();
        long period = Math.max(200, properties.leaseDuration().toMillis() / 3);
        ScheduledFuture<?> beat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!lost.get() && heartbeat(lease) != 1) {
                    lost.set(true);
                    log.warn("Production request {} lease epoch {} could not be renewed", lease.requestId(),
                            lease.epoch());
                }
            } catch (RuntimeException e) {
                log.warn("Production request {} heartbeat failed", lease.requestId(), e);
            }
        }, period, period, TimeUnit.MILLISECONDS);
        try {
            return task.get();
        } finally {
            beat.cancel(false);
        }
    }

    int heartbeat(Lease lease) {
        return transactions.execute(() -> mapper.heartbeat(lease.requestId(), lease.owner(), lease.epoch(),
                leaseMicros()));
    }

    private StageWork.StepContext context(RequestRow request, StepRow step, int attempt) {
        return new StageWork.StepContext(request.id(), step.id(), step.stage(), step.targetKey(),
                step.inputGeneration(), attempt, request.channelId(), request.contentType(), request.contentId(),
                request.runId());
    }

    private static String requireResult(String result) {
        if (result == null || result.isBlank() || result.length() > 512) {
            throw new IllegalStateException("A local step must return a result reference of at most 512 chars");
        }
        return result;
    }

    private long leaseMicros() {
        return properties.leaseDuration().toNanos() / 1000;
    }

    private long recheckMicros() {
        return properties.recoveryRecheck().toNanos() / 1000;
    }

    private static boolean isTerminal(ProductionStatus status) {
        return status == ProductionStatus.SUCCEEDED || status == ProductionStatus.FAILED
                || status == ProductionStatus.CANCELLED;
    }

    private record Snapshot(RequestRow request, List<StepRow> steps) {}

    @Override
    public void close() {
        heartbeats.shutdownNow();
    }
}
