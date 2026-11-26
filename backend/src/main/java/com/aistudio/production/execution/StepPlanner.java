package com.aistudio.production.execution;

import java.util.List;
import java.util.function.Function;

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
