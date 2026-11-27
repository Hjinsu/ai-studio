package com.aistudio.production.execution;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.production.contract.StepStatus;
import com.aistudio.production.execution.ExecutionMapper.StepRow;

/**
 * Decides the next action for a leased request from its persisted state only, so a restarted process reaches the
 * same decision as the one that stopped. Order: recover unknown effects, settle an interrupted step, honour cancel,
 * stop on failure, then run the first unfinished stage of the fixed order.
 */
final class StepPlanner {
    sealed interface Action permits Recover, Execute, Fail, Finish, Release {}

    /** Re-present the same effect identity to the cost port. Never counts as a new attempt. */
    record Recover(StepRow step) implements Action {}

    /** Start a new attempt of the step. */
    record Execute(StepRow step) implements Action {}

    /** Close the step as failed without running it (attempt limit reached). */
    record Fail(StepRow step, String code) implements Action {}

    record Finish(ProductionStatus status) implements Action {}

    /** Nothing is due now; release the lease. */
    record Release() implements Action {}

    private StepPlanner() {}

    static Action next(List<StepRow> steps, boolean cancelRequested, Function<ProductionStage, StageWork.Kind> kinds) {
        return next(steps, cancelRequested, false, kinds);
    }

    /**
     * @param awaitingChoice V2-02 MANUAL discovery request without a recorded choice: a pending SELECT is not
     *        started and the lease is released. Recovery, an interrupted step and cancel still come first.
     */
    static Action next(List<StepRow> steps, boolean cancelRequested, boolean awaitingChoice,
            Function<ProductionStage, StageWork.Kind> kinds) {
        return next(steps, cancelRequested, awaitingChoice, kinds, step -> false);
    }

    /**
     * @param persisted true for an interrupted local step whose result was already committed before the stop (V2-04
     *        B02). Such a step is recovered from that stored result without a new attempt, so a stop after the
     *        result commit of the last allowed attempt does not fail an otherwise finished step. Whether the stored
     *        result is intact is verified by the recovery itself; without a stored result the attempt limit applies.
     */
    static Action next(List<StepRow> steps, boolean cancelRequested, boolean awaitingChoice,
            Function<ProductionStage, StageWork.Kind> kinds, Predicate<StepRow> persisted) {
        requireFixedOrder(steps);
        for (StepRow step : steps) {
            if (step.status() == StepStatus.RECOVERY_REQUIRED) {
                return new Recover(step);
            }
        }
        for (StepRow step : steps) {
            if (step.status() == StepStatus.RUNNING) {
                // The previous lease holder stopped inside this step.
                if (kinds.apply(step.stage()) == StageWork.Kind.EFFECT) {
                    return new Recover(step);
                }
                if (cancelRequested) {
                    return new Finish(ProductionStatus.CANCELLED);
                }
                if (persisted.test(step)) {
                    return new Recover(step);
                }
                return step.attempts() >= ExecutionProperties.MAX_ATTEMPTS
                        ? new Fail(step, "INTERRUPTED") : new Execute(step);
            }
        }
        if (cancelRequested) {
            return new Finish(ProductionStatus.CANCELLED);
        }
        for (StepRow step : steps) {
            if (step.status() == StepStatus.FAILED || step.status() == StepStatus.CANCELLED) {
                return new Finish(ProductionStatus.FAILED);
            }
        }
        for (StepRow step : steps) {
            if (step.status() == StepStatus.SUCCEEDED) {
                continue;
            }
            if (awaitingChoice && step.stage() == ProductionStage.SELECT && step.status() == StepStatus.PENDING) {
                return new Release();
            }
            if (step.status() == StepStatus.RETRY_WAIT && !step.retryDue()) {
                return new Release();
            }
            return step.attempts() >= ExecutionProperties.MAX_ATTEMPTS
                    ? new Fail(step, "ATTEMPTS_EXHAUSTED") : new Execute(step);
        }
        return new Finish(ProductionStatus.SUCCEEDED);
    }

    private static void requireFixedOrder(List<StepRow> steps) {
        ProductionStage[] stages = ProductionStage.values();
        if (steps.size() != stages.length) {
            throw new IllegalStateException("A production request must have exactly " + stages.length + " steps");
        }
        for (int i = 0; i < stages.length; i++) {
            StepRow step = steps.get(i);
            if (step.stage() != stages[i] || step.inputGeneration() != 1) {
                throw new IllegalStateException("Steps are not in the fixed V2-01 order");
            }
        }
    }
}
