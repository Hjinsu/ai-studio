package com.aistudio.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aistudio.queue.JobTransitions.Completion;
import com.aistudio.queue.JobTransitions.Decision;
import com.aistudio.queue.JobTransitions.JobFacts;
import com.aistudio.queue.JobTransitions.OperationPhase;
import com.aistudio.queue.JobTransitions.Resolution;

/** J02~J28 판정과 FD-09(자동 3회, 최초 포함)·FD-10(수동 2회)·최종 명세의 재발급 금지. */
class JobTransitionsTest {

    private static JobFacts facts(int autoUsed, int manualUsed, boolean cancel, boolean failFast, boolean op) {
        return new JobFacts(op, autoUsed, 3, manualUsed, 2, cancel, failFast);
    }

    @Test
    void successIsSuccessEvenWhenCancelRequested() {
        assertThat(JobTransitions.complete(facts(1, 0, true, false, false), Completion.SUCCEEDED, null).to())
                .isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void retryableFollowsAutoThenManualThenFailed() {
        Decision first = JobTransitions.complete(facts(1, 0, false, false, false), Completion.RETRYABLE, "TIMEOUT");
        assertThat(first.to()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(first.retry()).isTrue();
        assertThat(first.reason()).isEqualTo("TIMEOUT");
        assertThat(JobTransitions.complete(facts(2, 0, false, false, false), Completion.RETRYABLE, null).to())
                .isEqualTo(JobStatus.RETRY_WAIT);
        // 최초 포함 3회 소진 → 수동 재개 대기
        Decision exhausted = JobTransitions.complete(facts(3, 0, false, false, false), Completion.RETRYABLE, null);
        assertThat(exhausted.to()).isEqualTo(JobStatus.ACTION_REQUIRED);
        assertThat(exhausted.reason()).isEqualTo("AUTO_ATTEMPTS_EXHAUSTED");
        // 수동 2회까지 소진 → FAILED + fail-fast
        Decision failed = JobTransitions.complete(facts(3, 2, false, false, false), Completion.RETRYABLE, null);
        assertThat(failed.to()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.reason()).isEqualTo("ATTEMPTS_EXHAUSTED");
        assertThat(failed.failFast()).isTrue();
    }

    @Test
    void businessErrorsAreNeverRetriedAutomatically() {
        for (String code : List.of("BUDGET_EXCEEDED", "POLICY_VIOLATION", "PREREQUISITE_NOT_MET")) {
            Decision d = JobTransitions.complete(facts(1, 0, false, false, false), Completion.ACTION_REQUIRED, code);
            assertThat(d.to()).isEqualTo(JobStatus.ACTION_REQUIRED);
            assertThat(d.reason()).isEqualTo(code);
            assertThat(d.retry()).isFalse();
        }
    }

    @Test
    void cancelAndFailFastStopRetries() {
        Decision cancelled = JobTransitions.complete(facts(1, 0, true, false, false), Completion.RETRYABLE, null);
        assertThat(cancelled.to()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.operationCancelRequest()).isTrue();
        Decision ff = JobTransitions.complete(facts(1, 0, false, true, false), Completion.ACTION_REQUIRED, null);
        assertThat(ff.to()).isEqualTo(JobStatus.FAILED);
        assertThat(ff.reason()).isEqualTo("RUN_FAILED_FAST_NO_RETRY");
    }

    @Test
    void outcomeUnknownOnlyForOperationJobsAndNeverRecalled() {
        Decision recovery = JobTransitions.complete(facts(1, 0, false, false, true), Completion.OUTCOME_UNKNOWN, null);
        assertThat(recovery.to()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        assertThat(recovery.retry()).isFalse();
        Decision violation = JobTransitions.complete(facts(1, 0, false, false, false), Completion.OUTCOME_UNKNOWN, null);
        assertThat(violation.to()).isEqualTo(JobStatus.FAILED);
        assertThat(violation.attemptOutcome()).isEqualTo(AttemptOutcome.FATAL_ERROR);
    }

    @Test
    void leaseExpiryRetriesOnlyBeforeDispatch() {
        Decision none = JobTransitions.expired(facts(1, 0, false, false, false), OperationPhase.NONE, false);
        assertThat(none.to()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(none.attemptOutcome()).isEqualTo(AttemptOutcome.LEASE_EXPIRED);
        Decision deadline = JobTransitions.expired(facts(3, 2, false, false, false), OperationPhase.NONE, true);
        assertThat(deadline.to()).isEqualTo(JobStatus.FAILED);
        assertThat(deadline.attemptOutcome()).isEqualTo(AttemptOutcome.DEADLINE_EXCEEDED);
        Decision inflight = JobTransitions.expired(facts(1, 0, false, false, true), OperationPhase.INFLIGHT, false);
        assertThat(inflight.to()).isEqualTo(JobStatus.RECOVERY_REQUIRED);
        Decision preCancel = JobTransitions.expired(facts(1, 0, true, false, true), OperationPhase.PRE, false);
        assertThat(preCancel.to()).isEqualTo(JobStatus.CANCELLED);
        assertThat(preCancel.operationCancelRequest()).isTrue();
        assertThatThrownBy(() -> JobTransitions.expired(facts(1, 0, false, false, false), OperationPhase.INFLIGHT,
                false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recoveryResolutionNeverReissuesTheOperation() {
        JobFacts f = facts(1, 0, false, false, true);
        assertThat(JobTransitions.resolve(f, Resolution.EXECUTED_RESULT_ADOPTED).to()).isEqualTo(JobStatus.SUCCEEDED);
        // 허가 이후 NOT_EXECUTED: 자동 시도가 남아도 재시도하지 않는다
        Decision notExecuted = JobTransitions.resolve(f, Resolution.NOT_EXECUTED);
        assertThat(notExecuted.to()).isEqualTo(JobStatus.FAILED);
        assertThat(notExecuted.retry()).isFalse();
        assertThat(JobTransitions.resolve(facts(1, 0, true, false, true), Resolution.NOT_EXECUTED).to())
                .isEqualTo(JobStatus.CANCELLED);
        assertThat(JobTransitions.resolve(f, Resolution.EXECUTED_FAILED).to()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void manualResumeRules() {
        assertThat(JobTransitions.canResume(JobStatus.ACTION_REQUIRED, facts(3, 1, false, false, false))).isTrue();
        assertThat(JobTransitions.canResume(JobStatus.ACTION_REQUIRED, facts(3, 2, false, false, false))).isFalse();
        assertThat(JobTransitions.canResume(JobStatus.RECOVERY_REQUIRED, facts(1, 0, false, false, true))).isFalse();
        assertThat(JobTransitions.canResume(JobStatus.ACTION_REQUIRED, facts(3, 0, true, false, false))).isFalse();
    }

    @Test
    void waitingPromotionAndCascade() {
        assertThat(JobTransitions.evaluateWaiting(List.of(JobStatus.SUCCEEDED, JobStatus.SUCCEEDED), false, false))
                .isEqualTo(JobStatus.READY);
        assertThat(JobTransitions.evaluateWaiting(List.of(JobStatus.SUCCEEDED, JobStatus.RUNNING), false, false))
                .isNull();
        assertThat(JobTransitions.evaluateWaiting(List.of(JobStatus.SUCCEEDED, JobStatus.FAILED), false, false))
                .isEqualTo(JobStatus.SKIPPED);
        assertThat(JobTransitions.evaluateWaiting(List.of(JobStatus.CANCELLED, JobStatus.FAILED), false, false))
                .isEqualTo(JobStatus.CANCELLED);
        assertThat(JobTransitions.evaluateWaiting(List.of(JobStatus.RUNNING), true, false))
                .isEqualTo(JobStatus.CANCELLED);
        assertThat(JobTransitions.evaluateWaiting(List.of(), false, false)).isEqualTo(JobStatus.READY);
    }

    @Test
    void runOutcomePriority() {
        assertThat(JobTransitions.runOutcome(List.of(JobStatus.SUCCEEDED, JobStatus.SUCCEEDED), true))
                .isEqualTo("SUCCEEDED");
        assertThat(JobTransitions.runOutcome(List.of(JobStatus.SUCCEEDED, JobStatus.CANCELLED), true))
                .isEqualTo("CANCELLED");
        assertThat(JobTransitions.runOutcome(List.of(JobStatus.FAILED, JobStatus.SKIPPED), false)).isEqualTo("FAILED");
        assertThatThrownBy(() -> JobTransitions.runOutcome(List.of(JobStatus.RUNNING), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
