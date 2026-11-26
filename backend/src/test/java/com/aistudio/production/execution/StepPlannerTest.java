package com.aistudio.production.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.aistudio.production.contract.ProductionStage;
import com.aistudio.production.contract.ProductionStatus;
import com.aistudio.production.contract.StepStatus;
import com.aistudio.production.execution.ExecutionMapper.StepRow;
import com.aistudio.production.execution.StepPlanner.Action;

class StepPlannerTest {
    private static final Function<ProductionStage, StageWork.Kind> KINDS = new FakeStageWork(BigDecimal.ONE)::kind;

    private static List<StepRow> steps() {
        List<StepRow> rows = new ArrayList<>();
        for (ProductionStage stage : ProductionStage.values()) {
            rows.add(new StepRow(stage.ordinal() + 1, stage, "video", 1, StepStatus.PENDING, 0, true));
        }
        return rows;
    }

    private static List<StepRow> with(List<StepRow> rows, ProductionStage stage, StepStatus status, int attempts,
            boolean due) {
        StepRow old = rows.get(stage.ordinal());
        rows.set(stage.ordinal(), new StepRow(old.id(), stage, old.targetKey(), 1, status, attempts, due));
        return rows;
    }

    private static List<StepRow> succeededUntil(ProductionStage exclusive) {
        List<StepRow> rows = steps();
        for (ProductionStage stage : ProductionStage.values()) {
            if (stage == exclusive) {
                break;
            }
            with(rows, stage, StepStatus.SUCCEEDED, 1, true);
        }
        return rows;
    }

    private static Action next(List<StepRow> rows, boolean cancel) {
        return StepPlanner.next(rows, cancel, KINDS);
    }

    @Test
    void manualChoiceWaitReleasesAPendingSelectWithoutStartingIt() {
        List<StepRow> rows = succeededUntil(ProductionStage.SELECT);
        assertThat(StepPlanner.next(rows, false, true, KINDS)).isEqualTo(new StepPlanner.Release());
        assertThat(StepPlanner.next(rows, false, false, KINDS))
                .isEqualTo(new StepPlanner.Execute(rows.get(ProductionStage.SELECT.ordinal())));
    }

    @Test
    void manualChoiceWaitNeverHoldsBackCancelRecoveryOrFailure() {
        List<StepRow> rows = succeededUntil(ProductionStage.SELECT);
        assertThat(StepPlanner.next(rows, true, true, KINDS)).isEqualTo(new StepPlanner.Finish(ProductionStatus.CANCELLED));
        List<StepRow> recovering = with(succeededUntil(ProductionStage.SELECT), ProductionStage.DISCOVER,
                StepStatus.RECOVERY_REQUIRED, 1, true);
        assertThat(StepPlanner.next(recovering, false, true, KINDS))
                .isEqualTo(new StepPlanner.Recover(recovering.get(0)));
        List<StepRow> failed = with(steps(), ProductionStage.DISCOVER, StepStatus.FAILED, 1, true);
        assertThat(StepPlanner.next(failed, false, true, KINDS)).isEqualTo(new StepPlanner.Finish(ProductionStatus.FAILED));
        // the wait only concerns SELECT: DISCOVER itself still runs
        assertThat(StepPlanner.next(steps(), false, true, KINDS)).isEqualTo(new StepPlanner.Execute(steps().get(0)));
    }

    @Test
    void runsTheFirstUnfinishedStageInFixedOrder() {
        assertThat(next(steps(), false)).isEqualTo(new StepPlanner.Execute(steps().get(0)));
        List<StepRow> rows = succeededUntil(ProductionStage.RENDER);
        assertThat(next(rows, false)).isEqualTo(new StepPlanner.Execute(rows.get(ProductionStage.RENDER.ordinal())));
    }

    @Test
    void finishesWhenAllStagesSucceeded() {
        assertThat(next(succeededUntil(null), false)).isEqualTo(new StepPlanner.Finish(ProductionStatus.SUCCEEDED));
    }

    @Test
    void waitsForRetryAndRunsItWhenDue() {
        List<StepRow> waiting = with(succeededUntil(ProductionStage.SUBTITLE), ProductionStage.SUBTITLE,
                StepStatus.RETRY_WAIT, 1, false);
        assertThat(next(waiting, false)).isInstanceOf(StepPlanner.Release.class);
        List<StepRow> due = with(succeededUntil(ProductionStage.SUBTITLE), ProductionStage.SUBTITLE,
                StepStatus.RETRY_WAIT, 2, true);
        assertThat(next(due, false)).isEqualTo(new StepPlanner.Execute(due.get(ProductionStage.SUBTITLE.ordinal())));
    }

    @Test
    void neverStartsAFourthAttempt() {
        List<StepRow> interrupted = with(succeededUntil(ProductionStage.RENDER), ProductionStage.RENDER,
                StepStatus.RUNNING, 3, true);
        assertThat(next(interrupted, false)).isEqualTo(
                new StepPlanner.Fail(interrupted.get(ProductionStage.RENDER.ordinal()), "INTERRUPTED"));
        List<StepRow> exhausted = with(succeededUntil(ProductionStage.RENDER), ProductionStage.RENDER,
                StepStatus.RETRY_WAIT, 3, true);
        assertThat(next(exhausted, false)).isInstanceOf(StepPlanner.Fail.class);
    }

    @Test
    void interruptedLocalStepIsRetriedButInterruptedEffectIsRecovered() {
        List<StepRow> local = with(succeededUntil(ProductionStage.RENDER), ProductionStage.RENDER,
                StepStatus.RUNNING, 1, true);
        assertThat(next(local, false)).isInstanceOf(StepPlanner.Execute.class);
        List<StepRow> effect = with(succeededUntil(ProductionStage.SCRIPT), ProductionStage.SCRIPT,
                StepStatus.RUNNING, 1, true);
        assertThat(next(effect, false)).isEqualTo(new StepPlanner.Recover(effect.get(ProductionStage.SCRIPT.ordinal())));
        // cancel does not skip an effect whose outcome is unknown
        assertThat(next(effect, true)).isInstanceOf(StepPlanner.Recover.class);
    }

    @Test
    void recoveryComesBeforeCancelAndFailure() {
        List<StepRow> rows = with(succeededUntil(ProductionStage.IMAGE), ProductionStage.IMAGE,
                StepStatus.RECOVERY_REQUIRED, 1, true);
        assertThat(next(rows, true)).isEqualTo(new StepPlanner.Recover(rows.get(ProductionStage.IMAGE.ordinal())));
    }

    @Test
    void cancelFinishesAsCancelledAndFailureAsFailed() {
        assertThat(next(steps(), true)).isEqualTo(new StepPlanner.Finish(ProductionStatus.CANCELLED));
        List<StepRow> runningLocal = with(succeededUntil(ProductionStage.SUBTITLE), ProductionStage.SUBTITLE,
                StepStatus.RUNNING, 1, true);
        assertThat(next(runningLocal, true)).isEqualTo(new StepPlanner.Finish(ProductionStatus.CANCELLED));
        List<StepRow> failed = with(succeededUntil(ProductionStage.TTS), ProductionStage.TTS, StepStatus.FAILED, 1,
                true);
        assertThat(next(failed, false)).isEqualTo(new StepPlanner.Finish(ProductionStatus.FAILED));
    }

    @Test
    void rejectsStepsOutsideTheFixedOrder() {
        List<StepRow> rows = steps();
        rows.remove(3);
        assertThatThrownBy(() -> next(rows, false)).isInstanceOf(IllegalStateException.class);
        List<StepRow> swapped = steps();
        StepRow first = swapped.get(0);
        swapped.set(0, swapped.get(1));
        swapped.set(1, first);
        assertThatThrownBy(() -> next(swapped, false)).isInstanceOf(IllegalStateException.class);
    }
}
