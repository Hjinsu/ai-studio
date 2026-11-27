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

import com.aistudio.production.contract.DiscoveryEvaluator.CatalogEntry;
import com.aistudio.production.contract.DiscoveryExecutionPort.SelectionBatch;
import com.aistudio.production.contract.EffectKey;
import com.aistudio.production.contract.MediaStageWork;
import com.aistudio.production.contract.PlanningExecutionPort;
import com.aistudio.production.contract.ProductionPlanning;
import com.aistudio.production.contract.ProductionPlanning.ResearchInput;
import com.aistudio.production.contract.ProductionPlanning.ScenesInput;
import com.aistudio.production.contract.ProductionPlanning.ScriptInput;
import com.aistudio.production.contract.ProductionPlanning.Validation;
import com.aistudio.production.contract.ProductionDiscovery.EvaluatedCandidate;
import com.aistudio.production.contract.ProductionDiscovery.Input;
import com.aistudio.production.contract.ProductionDiscovery.Mode;
import com.aistudio.production.contract.ProductionDiscovery.Response;
import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.discovery.DiscoveryInputs;
import com.aistudio.production.discovery.DiscoveryResponseException;
import com.aistudio.production.discovery.DiscoveryResponseValidator;
import com.aistudio.production.discovery.DiscoverySelections;
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
    private final DiscoveryFlow discovery;
    private final PlanningFlow planning;
    private final MediaFlow media;

    public ProductionExecutor(ExecutionMapper mapper, BudgetControlLock control, LockRetryingTransactions transactions,
            ProductionCostPort cost, ProductionSelectionPort selection, StageWork work,
            ExecutionProperties properties) {
        this(mapper, control, transactions, cost, selection, work, properties, null);
    }

    /** @param discovery V2-02 discovery flow; {@code null} keeps the executor on {@code v2-fixed-1} requests only */
    public ProductionExecutor(ExecutionMapper mapper, BudgetControlLock control, LockRetryingTransactions transactions,
            ProductionCostPort cost, ProductionSelectionPort selection, StageWork work,
            ExecutionProperties properties, DiscoveryFlow discovery) {
        this(mapper, control, transactions, cost, selection, work, properties, discovery, null);
    }

    /**
     * @param planning V2-03 planning flow; {@code null} (the default) never claims {@code v2-planning-1} requests.
     *        It needs the discovery flow, because planning requests discover and select their topic first.
     */
    public ProductionExecutor(ExecutionMapper mapper, BudgetControlLock control, LockRetryingTransactions transactions,
            ProductionCostPort cost, ProductionSelectionPort selection, StageWork work,
            ExecutionProperties properties, DiscoveryFlow discovery, PlanningFlow planning) {
        this(mapper, control, transactions, cost, selection, work, properties, discovery, planning, null);
    }

    ProductionExecutor(ExecutionMapper mapper, BudgetControlLock control, LockRetryingTransactions transactions,
            ProductionCostPort cost, ProductionSelectionPort selection, StageWork work,
            ExecutionProperties properties, DiscoveryFlow discovery, PlanningFlow planning, MediaFlow media) {
        StageWork.validate(work);
        if (planning != null && discovery == null) {
            throw new IllegalArgumentException("The planning flow requires the discovery flow");
        }
        this.discovery = discovery;
        this.planning = planning;
        this.media = media;
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
        boolean mediaReady = media != null && media.ready();
        return transactions.execute(() -> {
            control.exclusive();
            if (mapper.countValidLeases() >= properties.maxLeases()) {
                return Optional.<Lease>empty();
            }
            Long id = mapper.nextClaimable(discovery != null, planning != null,
                    mediaReady, media != null);
            if (id == null || mapper.claim(id, owner, leaseMicros()) != 1) {
                return Optional.<Lease>empty();
            }
            return Optional.of(new Lease(id, owner, mapper.read(id).leaseEpoch()));
        });
    }

    void drive(Lease lease) {
        for (int i = 0; i < MAX_ACTIONS_PER_CLAIM; i++) {
            Snapshot snapshot = fenced(lease, request -> new Snapshot(request, mapper.steps(request.id()),
                    request.discovery() && mapper.awaitingManualChoice(request.id())));
            if (isTerminal(snapshot.request().status())) {
                return;
            }
            if (snapshot.request().discovery() && discovery == null) {
                log.error("Production request {} needs the discovery flow, which is not wired; releasing",
                        lease.requestId());
                release(lease);
                return;
            }
            if (snapshot.request().planning() && planning == null) {
                log.error("Production request {} needs the planning flow, which is not wired; releasing",
                        lease.requestId());
                release(lease);
                return;
            }
            StepPlanner.Action action = StepPlanner.next(snapshot.steps(), snapshot.request().cancelRequested(),
                    snapshot.awaitingChoice(), stage -> snapshot.request().media() && MediaFlow.stage(stage)
                            ? (MediaFlow.paidStage(stage) ? StageWork.Kind.EFFECT : StageWork.Kind.LOCAL)
                            : work.kind(stage));
            switch (action) {
                case StepPlanner.Recover recover -> {
                    boolean more = isDiscover(snapshot.request(), recover.step())
                            ? runDiscover(lease, snapshot.request(), recover.step(), 0)
                            : isPlanning(snapshot.request(), recover.step())
                                    ? runPlanning(lease, snapshot.request(), recover.step(), 0)
                                    : snapshot.request().media() && MediaFlow.stage(recover.step().stage())
                                            ? runMedia(lease, snapshot.request(), recover.step(), true)
                                            : presentEffect(lease, snapshot.request(), recover.step(), 0);
                    if (!more) {
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
        if (request.discovery() && step.stage() == ProductionStage.DISCOVER) {
            return runDiscover(lease, request, step, 1);
        }
        if (request.discovery() && step.stage() == ProductionStage.SELECT) {
            return runDiscoverySelect(lease, request, step);
        }
        if (isPlanning(request, step)) {
            return runPlanning(lease, request, step, 1);
        }
        if (request.media() && MediaFlow.stage(step.stage())) {
            return runMedia(lease, request, step, false);
        }
        return switch (work.kind(step.stage())) {
            case EFFECT -> presentEffect(lease, request, step, 1);
            case LOCAL -> runLocal(lease, request, step);
            case SELECT -> runSelect(lease, request, step);
        };
    }

    private boolean runMedia(Lease lease, RequestRow request, StepRow step, boolean recover) {
        try {
            return runMediaStage(lease, request, step, recover);
        } finally {
            if (media != null) {
                media.finishStage(lease.requestId());
            }
        }
    }

    private boolean runMediaStage(Lease lease, RequestRow request, StepRow step, boolean recover) {
        if (media == null) {
            park(lease, step, "MEDIA_UNAVAILABLE");
            return false;
        }
        boolean recoveryOnly = !media.ready() || request.cancelRequested();
        if (recoveryOnly && !recover) {
            release(lease);
            return false;
        }
        int attempt = start(lease, step, recover ? 0 : 1);
        MediaStageWork.Result result;
        try {
            var frozen = fenced(lease, locked -> media.freeze(lease.stepOwner(step.id()), step.stage(), recoveryOnly));
            var context = new MediaStageWork.Context(lease.stepOwner(step.id()), request.channelId(),
                    request.contentId(), request.runId(), frozen.revisionId(), attempt, step.stage(),
                    frozen.profile().json(), frozen.profile().hash(), frozen.items(), mediaCancellation(lease),
                    recoveryOnly);
            result = withHeartbeat(lease, () -> {
                MediaStageWork.Result prepared = recoveryOnly ? media.recoverCosts(context)
                        : recover ? media.work().recover(context) : media.work().execute(context);
                if (step.stage() == ProductionStage.QC && prepared.outcome() == MediaStageWork.Outcome.APPLIED
                        && !context.cancelled().getAsBoolean()) {
                    try {
                        media.verifyQc(context);
                    } catch (java.io.IOException e) {
                        return new MediaStageWork.Result(MediaStageWork.Outcome.BLOCKED, "MEDIA_QC_INTEGRITY");
                    }
                }
                return prepared;
            });
        } catch (LeaseLostException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Media step {} interrupted", step.id(), e);
            result = new MediaStageWork.Result(MediaFlow.paidStage(step.stage())
                    ? MediaStageWork.Outcome.RECOVERY_REQUIRED : MediaStageWork.Outcome.RETRY, "MEDIA_INTERRUPTED");
        }
        String code = result.code() == null || !result.code().matches("[A-Z0-9_]{1,64}") ? "MEDIA_FAILED" : result.code();
        if (result.outcome() == MediaStageWork.Outcome.RECOVERY_REQUIRED && MediaFlow.paidStage(step.stage())) {
            park(lease, step, code);
            return false;
        }
        var outcome = result.outcome();
        boolean applied = fenced(lease, locked -> {
            CostState state = cost.readCostState(locked.id());
            if (state.unresolved() || !state.unappliedReceipts().isEmpty()) {
                return false;
            }
            if (locked.cancelRequested()) {
                mapper.endStep(locked.id(), step.id(), StepStatus.CANCELLED, "CANCELLED");
            } else if (outcome == MediaStageWork.Outcome.APPLIED) {
                if (!media.applied(locked.id(), step.stage())) {
                    return false;
                }
                mapper.succeedStep(locked.id(), step.id(), "media:" + step.id());
            } else if (outcome == MediaStageWork.Outcome.RETRY && attempt < ExecutionProperties.MAX_ATTEMPTS) {
                mapper.waitStep(locked.id(), step.id(), StepStatus.RETRY_WAIT, code, recheckMicros());
            } else {
                mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, code);
            }
            resumeRequest(locked);
            return true;
        });
        if (!applied) {
            park(lease, step, "MEDIA_APPLY_PENDING");
        }
        return applied;
    }

    private java.util.function.BooleanSupplier mediaCancellation(Lease lease) {
        return new java.util.function.BooleanSupplier() {
            private long checkedAt;
            private boolean stopped;

            @Override
            public synchronized boolean getAsBoolean() {
                long now = System.nanoTime();
                if (!stopped && (checkedAt == 0 || now - checkedAt >= TimeUnit.MILLISECONDS.toNanos(100))) {
                    RequestRow current = mapper.read(lease.requestId());
                    stopped = current == null || !current.leaseValid() || current.leaseEpoch() != lease.epoch()
                            || !lease.owner().equals(current.leaseOwner()) || current.cancelRequested();
                    checkedAt = now;
                }
                return stopped;
            }
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
        return dispatch(lease, step, () -> cost.executeEffect(lease.stepOwner(step.id()), effectKey, requestHash,
                priced), receipt -> applyReceipt(lease, step, receipt));
    }

    /** Presents an effect through a cost-guarded call and settles its disposition. */
    private boolean dispatch(Lease lease, StepRow step, Supplier<EffectResult> effect,
            Function<Receipt, Boolean> onReceipt) {
        EffectResult result;
        try {
            result = withHeartbeat(lease, effect);
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
                return onReceipt.apply(result.receipt());
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

    // ------------------------------------------------------------------ V2-02 discovery

    private static boolean isDiscover(RequestRow request, StepRow step) {
        return request.discovery() && step.stage() == ProductionStage.DISCOVER;
    }

    /**
     * DISCOVER of a discovery request: the snapshot input is presented through the discovery port, which owns
     * reservation, the single dispatch and the receipt. A recovery presentation re-presents the same input and does
     * not count an attempt. On an adopted receipt the stored response is verified, evaluated against the catalog
     * and committed together with the receipt and the step result.
     */
    private boolean runDiscover(Lease lease, RequestRow request, StepRow step, int attemptIncrement) {
        int attempt = start(lease, step, attemptIncrement);
        StepOwner owner = lease.stepOwner(step.id());
        Input input;
        try {
            input = discovery.port().input(request.id());
            DiscoveryInputs.validate(input);
        } catch (RuntimeException e) {
            if (attemptIncrement == 0) {
                log.warn("Production step {} discovery input unavailable for recovery", step.id(), e);
                park(lease, step, "EFFECT_PRICE_UNAVAILABLE");
                return false;
            }
            return localFailure(lease, step, attempt, e); // nothing was presented yet
        }
        return dispatch(lease, step, () -> discovery.port().discover(owner, input),
                receipt -> applyDiscovery(lease, step, input, receipt));
    }

    private boolean applyDiscovery(Lease lease, StepRow step, Input input, Receipt receipt) {
        if (receipt == null || receipt.outcome() == null) {
            park(lease, step, "RECEIPT_INVALID");
            return false;
        }
        if (receipt.outcome() != ProductionCostPort.Outcome.RESULT_ADOPTED) {
            return applyReceipt(lease, step, receipt); // executed-failed or not executed: no candidates to keep
        }
        StepOwner owner = lease.stepOwner(step.id());
        Response response;
        try {
            response = discovery.port().response(owner, receipt);
            DiscoveryResponseValidator.validate(input, response);
        } catch (DiscoveryResponseException invalid) {
            // The cost is confirmed; a response outside the contract is never kept as a discovery result.
            log.warn("Production step {} discovery response rejected: {}", step.id(), invalid.field());
            return failWithReceipt(lease, step, receipt, DiscoveryResponseException.CODE);
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            log.warn("Production step {} discovery response unavailable", step.id(), e);
            park(lease, step, "DISCOVERY_RESPONSE_UNAVAILABLE");
            return false;
        }
        List<EvaluatedCandidate> evaluations;
        try {
            List<CatalogEntry> catalog = discovery.port().catalog(lease.requestId(), response.candidates());
            evaluations = discovery.evaluator().evaluate(input, response, catalog);
        } catch (RuntimeException e) {
            log.warn("Production step {} discovery evaluation failed", step.id(), e);
            park(lease, step, "DISCOVERY_EVALUATION_FAILED");
            return false;
        }
        try {
            fenced(lease, locked -> {
                boolean manual = discovery.port().manualMode(owner.requestId());
                boolean eligible = evaluations.stream().anyMatch(candidate ->
                        manual ? candidate.manuallySelectable() : candidate.eligible());
                discovery.port().applyEvaluation(owner, receipt, evaluations);
                resumeRequest(locked);
                // R03: no eligible candidate fails DISCOVER; no further discovery or regeneration is attempted
                return eligible ? mapper.succeedStep(locked.id(), step.id(), "discovery:" + locked.id())
                        : mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, NO_ELIGIBLE);
            });
            return true;
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            log.warn("Production step {} discovery evaluation not applied; recheck later", step.id(), e);
            park(lease, step, "RECEIPT_APPLY_FAILED");
            return false;
        }
    }

    private boolean failWithReceipt(Lease lease, StepRow step, Receipt receipt, String code) {
        try {
            fenced(lease, locked -> {
                cost.markReceiptApplied(lease.stepOwner(step.id()), receipt.operationId());
                resumeRequest(locked);
                return mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, code);
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

    enum SelectOutcome { LINKED, WAITING, FAILED, CANCELLED }

    /**
     * SELECT of a discovery request (R04, R07), in one fenced transaction: request lock, then the discovery port's
     * channel lock, re-evaluation against the latest catalog, then either the link with the selection record and
     * SELECT completion, or (MANUAL) recording the invalidated choice and waiting again. The re-check runs before
     * the attempt is counted, so a choice invalidated by a catalog change does not use up an attempt. Any failure
     * rolls the whole transaction back and is then counted as one local attempt.
     */
    private boolean runDiscoverySelect(Lease lease, RequestRow request, StepRow step) {
        StepOwner owner = lease.stepOwner(step.id());
        SelectOutcome outcome;
        try {
            Input input = discovery.port().input(request.id());
            DiscoveryInputs.validate(input);
            outcome = fenced(lease, locked -> selectInTransaction(owner, locked, step, input));
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            int attempt = start(lease, step, 1);
            return localFailure(lease, step, attempt, e);
        }
        log.info("Production request {} discovery SELECT: {}", request.id(), outcome);
        return true; // the planner decides next from the committed state (finish, wait or cancel)
    }

    private SelectOutcome selectInTransaction(StepOwner owner, RequestRow locked, StepRow step, Input input) {
        if (locked.cancelRequested()) {
            return SelectOutcome.CANCELLED; // never link after cancel; the planner cancels the request
        }
        SelectionBatch batch = discovery.port().lockForSelection(owner);
        if (batch == null || batch.candidates() == null || batch.candidates().isEmpty()) {
            throw new IllegalStateException("No evaluated discovery batch for request " + locked.id());
        }
        // The stored batch is rebuilt for evaluation only; the fingerprint uses the hash the adapter verified
        // against the applied receipt.
        Response response = DiscoverySelections.response(batch.candidates());
        if (batch.responseHash() == null || batch.responseHash().isBlank()) {
            throw new IllegalStateException("No verified discovery response hash for request " + locked.id());
        }
        List<CatalogEntry> catalog = discovery.port().catalog(locked.id(), response.candidates());
        List<EvaluatedCandidate> fresh = DiscoverySelections.merge(batch.candidates(),
                discovery.evaluator().evaluate(input, response, catalog));
        List<EvaluatedCandidate> ranked = DiscoverySelections.ranked(fresh);
        EvaluatedCandidate chosen;
        if (batch.mode() == Mode.MANUAL) {
            if (batch.chosenCandidateId() == null) {
                awaitChoice(locked, step);
                return SelectOutcome.WAITING;
            }
            chosen = fresh.stream().filter(c -> c.candidateId() == batch.chosenCandidateId())
                    .filter(c -> batch.permitsManualChoice(c)).findFirst().orElse(null);
            if (chosen == null) {
                discovery.port().invalidateManualChoice(owner, batch.version(), fresh);
                if (fresh.stream().anyMatch(EvaluatedCandidate::manuallySelectable)) {
                    awaitChoice(locked, step); // remaining eligible candidates: the requester chooses again
                    return SelectOutcome.WAITING;
                }
                startCounted(locked, step);
                mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, NO_ELIGIBLE);
                return SelectOutcome.FAILED;
            }
        } else {
            if (ranked.isEmpty()) {
                // keep the latest catalog reasons next to the first evaluation before failing
                discovery.port().recordExhaustedSelection(owner, batch.version(), fresh);
                startCounted(locked, step);
                mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, NO_ELIGIBLE);
                return SelectOutcome.FAILED;
            }
            chosen = ranked.get(0);
        }
        startCounted(locked, step);
        ProductionSelectionPort.Selection value = DiscoverySelections.selection(input, chosen, batch.responseHash());
        ProductionSelectionPort.Selected selected = selection.link(owner, value);
        discovery.port().recordSelection(owner, batch.version(), chosen.candidateId(),
                DiscoverySelections.rationale(batch.mode(), chosen, ranked.size()), fresh);
        mapper.startRun(selected.contentId(), selected.runId());
        if (mapper.succeedStep(locked.id(), step.id(), "run:" + selected.runId()) != 1) {
            throw new IllegalStateException("SELECT step " + step.id() + " could not complete");
        }
        return SelectOutcome.LINKED;
    }

    /**
     * A MANUAL request waits for the requester with SELECT PENDING, the condition the claim query, the planner and
     * the API use. A SELECT waiting for a local retry goes back to PENDING without a retry time; its attempts are
     * kept, so the next attempt after a new choice counts on top of them.
     */
    private void awaitChoice(RequestRow locked, StepRow step) {
        mapper.awaitChoice(locked.id(), step.id());
    }

    private void startCounted(RequestRow locked, StepRow step) {
        if (mapper.startStep(locked.id(), step.id(), 1) != 1) {
            throw new IllegalStateException("Step " + step.id() + " cannot start");
        }
    }

    static final String NO_ELIGIBLE = "DISCOVERY_NO_ELIGIBLE_CANDIDATE";

    // ------------------------------------------------------------------ V2-03 planning

    private static boolean isPlanning(RequestRow request, StepRow step) {
        return request.planning() && (step.stage() == ProductionStage.RESEARCH
                || step.stage() == ProductionStage.SCRIPT || step.stage() == ProductionStage.SCENES);
    }

    /**
     * RESEARCH, SCRIPT or SCENES of a planning request. The fixed input comes from the planning port (first built
     * from the applied previous step, then the stored copy), and the effect is presented only through the port,
     * which owns reservation, the single dispatch, response storage and settlement. A recovery presentation reuses
     * the stored input and does not count an attempt. The executor never calls a provider itself.
     */
    private boolean runPlanning(Lease lease, RequestRow request, StepRow step, int attemptIncrement) {
        int attempt = start(lease, step, attemptIncrement);
        StepOwner owner = lease.stepOwner(step.id());
        Object input;
        try {
            input = switch (step.stage()) {
                case RESEARCH -> planning.port().researchInput(request.id());
                case SCRIPT -> planning.port().scriptInput(request.id());
                case SCENES -> planning.port().scenesInput(request.id());
                default -> throw new IllegalStateException("Not a planning stage: " + step.stage());
            };
            if (input == null) {
                throw new IllegalStateException("No planning input for step " + step.id());
            }
        } catch (RuntimeException e) {
            if (attemptIncrement == 0) {
                log.warn("Production step {} planning input unavailable for recovery", step.id(), e);
                park(lease, step, "EFFECT_PRICE_UNAVAILABLE");
                return false;
            }
            return localFailure(lease, step, attempt, e); // nothing was presented yet
        }
        return dispatch(lease, step, () -> switch (step.stage()) {
            case RESEARCH -> planning.port().research(owner, (ResearchInput) input, planning.maximumKrw());
            case SCRIPT -> planning.port().script(owner, (ScriptInput) input, planning.maximumKrw());
            case SCENES -> planning.port().scenes(owner, (ScenesInput) input, planning.maximumKrw());
            default -> throw new IllegalStateException("Not a planning stage: " + step.stage());
        }, receipt -> applyPlanning(lease, step, input, receipt));
    }

    /**
     * Adopted planning receipt: read the stored response through the port, review it against the fixed input, then
     * in one fenced transaction store the review with the receipt (the port marks the receipt applied) and finish
     * the step. A review with a required block fails the step with {@link ProductionPlanning#BLOCKED}, which stops
     * the request before the next stage; a review with recommendations only is kept and the step succeeds. Nothing
     * is regenerated. A response that cannot be read or reviewed keeps the receipt unapplied for a recheck.
     */
    private boolean applyPlanning(Lease lease, StepRow step, Object input, Receipt receipt) {
        if (receipt == null || receipt.outcome() == null) {
            park(lease, step, "RECEIPT_INVALID");
            return false;
        }
        if (receipt.outcome() != ProductionCostPort.Outcome.RESULT_ADOPTED) {
            return applyReceipt(lease, step, receipt); // executed-failed or not executed: no result to review
        }
        StepOwner owner = lease.stepOwner(step.id());
        PlanningExecutionPort.CandidateResult candidate;
        try {
            candidate = planning.port().response(owner, receipt);
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            log.warn("Production step {} planning response unavailable", step.id(), e);
            park(lease, step, "PLANNING_RESPONSE_UNAVAILABLE");
            return false;
        }
        Validation validation;
        try {
            if (candidate == null || candidate.stage() != step.stage()) {
                throw new IllegalStateException("Planning response belongs to another stage");
            }
            validation = planning.review().review(step.stage(), input, candidate.body());
        } catch (RuntimeException e) {
            log.warn("Production step {} planning review failed", step.id(), e);
            park(lease, step, "PLANNING_REVIEW_FAILED");
            return false;
        }
        try {
            fenced(lease, locked -> {
                planning.port().apply(owner, receipt, validation);
                resumeRequest(locked);
                int changed = validation.blocked()
                        ? mapper.endStep(locked.id(), step.id(), StepStatus.FAILED, ProductionPlanning.BLOCKED)
                        : mapper.succeedStep(locked.id(), step.id(), "planning:" + receipt.operationId());
                if (changed != 1) {
                    throw new IllegalStateException("Planning step " + step.id() + " could not finish");
                }
                return changed;
            });
            if (validation.blocked()) {
                log.info("Production step {} {} blocked by review: {}", step.id(), step.stage(),
                        validation.findings().stream()
                                .filter(finding -> finding.severity() == ProductionPlanning.Severity.BLOCK)
                                .map(ProductionPlanning.Finding::code).distinct().toList());
            }
            return true;
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            log.warn("Production step {} planning receipt {} not applied; recheck later", step.id(),
                    receipt.operationId(), e);
            park(lease, step, "RECEIPT_APPLY_FAILED");
            return false;
        }
    }

    // ------------------------------------------------------------------ finish

    /**
     * The cost check and every completion write share one transaction that holds the request lock. Settlement
     * and evidence writers lock the request first, so the cost state cannot change between check and completion.
     * The cost port's reads join this transaction (READ COMMITTED, non-locking, no budget_control). Any failure
     * rolls the whole completion back and the request waits for a recheck.
     *
     * <p>The planned target comes from a snapshot taken in an earlier transaction. A cancel committed since then
     * (the cancel API writes under the same request lock) is re-read here from the locked row and wins: the request
     * finishes as CANCELLED, as the planner would decide from the current state.
     */
    private void finish(Lease lease, List<StepRow> steps, ProductionStatus planned) {
        long anchor = anchorStep(steps);
        boolean[] costRead = { false };
        ProductionStatus[] finished = { planned };
        String blocked;
        try {
            blocked = fenced(lease, locked -> {
                costRead[0] = false; // a lock-conflict retry repeats the whole transaction
                ProductionStatus target = locked.cancelRequested() ? ProductionStatus.CANCELLED : planned;
                finished[0] = target;
                CostState state = cost.readCostState(locked.id());
                costRead[0] = true;
                String reason = blockReason(state, target);
                if (reason != null) {
                    postpone(lease, locked, anchor);
                    return reason;
                }
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
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (RuntimeException e) {
            blocked = costRead[0] ? "FINISH_FAILED" : "COST_STATE_UNAVAILABLE";
            log.warn("Production request {} completion rolled back: {}", lease.requestId(), blocked, e);
            fenced(lease, locked -> postpone(lease, locked, anchor));
        }
        if (blocked != null) {
            log.warn("Production request {} cannot finish as {}: {}", lease.requestId(), finished[0], blocked);
            return;
        }
        log.info("Production request {} finished as {}", lease.requestId(), finished[0]);
    }

    private static String blockReason(CostState state, ProductionStatus target) {
        return state == null ? "COST_STATE_UNAVAILABLE"
                : state.unresolved() ? "COST_UNRESOLVED"
                : !state.unappliedReceipts().isEmpty() ? "RECEIPT_UNAPPLIED"
                : state.spendingBlocked() && target == ProductionStatus.SUCCEEDED ? "SPENDING_BLOCKED"
                : null;
    }

    /** Keeps the request and its run open, schedules a recheck and releases the lease. */
    private int postpone(Lease lease, RequestRow locked, long anchor) {
        mapper.scheduleRecheck(locked.id(), anchor, recheckMicros());
        mapper.moveRequest(locked.id(), ProductionStatus.RUNNING, ProductionStatus.RECOVERY_REQUIRED);
        return mapper.release(locked.id(), lease.owner(), lease.epoch());
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

    /** Package-private so a test can act between the planning snapshot and the completion transaction. */
    record Snapshot(RequestRow request, List<StepRow> steps, boolean awaitingChoice) {}

    @Override
    public void close() {
        heartbeats.shutdownNow();
    }
}
