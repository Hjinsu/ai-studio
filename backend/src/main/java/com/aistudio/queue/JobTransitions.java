package com.aistudio.queue;

import java.util.Objects;

/**
 * Job 상태 전이 판정(순수 함수, DB·잠금 없음). T03 STATE 표와 A3 §4.1 J08~J28을 따르며 최종 명세의 변경분을 우선한다:
 * <ul>
 * <li>허가(dispatch) 이후 결과는 재발급하지 않는다. RECOVERY_REQUIRED에서 NOT_EXECUTED가 확인돼도 같은 operation으로
 * 재시도하지 않고 취소 의도면 CANCELLED, 아니면 FAILED다(A3 J23/J24를 대체, FD-04/05).</li>
 * <li>비용·정책·전제 오류(ACTION_REQUIRED)는 자동 재시도하지 않는다.</li>
 * <li>외부 결과 불명(OUTCOME_UNKNOWN)은 requires_operation job만 허용하고 재호출하지 않는다.</li>
 * </ul>
 * 실행 결과 분류·operation 분류(PRE/INFLIGHT/NONE)는 호출자가 넘긴다. 비용 판정은 T03-D 소관이다.
 */
public final class JobTransitions {

    private JobTransitions() {
    }

    /** 실행기가 보고한 완료 분류(A3 §6.5). */
    public enum Completion {
        SUCCEEDED, RETRYABLE, ACTION_REQUIRED, FATAL, OUTCOME_UNKNOWN, CANCELLED
    }

    /** job 미해결 operation 분류(T03 STATE PRE/INFLIGHT/NONE). T03-D가 제공한다. */
    public enum OperationPhase {
        NONE, PRE, INFLIGHT
    }

    /** RECOVERY_REQUIRED의 P12 해소 결과(T03-D receipt). */
    public enum Resolution {
        EXECUTED_RESULT_ADOPTED, EXECUTED_FAILED, NOT_EXECUTED
    }

    /** 판정 입력: 현재 카운터와 Run 신호. */
    public record JobFacts(boolean requiresOperation, int autoAttemptsUsed, int maxAutoAttempts, int manualResumesUsed,
            int maxManualResumes, boolean runCancelRequested, boolean runFailFast) {

        boolean autoLeft() {
            return autoAttemptsUsed < maxAutoAttempts;
        }

        boolean manualLeft() {
            return manualResumesUsed < maxManualResumes;
        }
    }

    /**
     * 판정 결과. {@code failFast}이면 Run fail_fast_at 최초 기록, {@code operationCancelRequest}이면 PRE operation 회수
     * 요청(T03-D), {@code retry}이면 backoff 후 available_at을 계산한다.
     */
    public record Decision(JobStatus to, String reason, AttemptOutcome attemptOutcome, boolean failFast,
            boolean operationCancelRequest, boolean retry) {
    }

    /** RUNNING job의 완료 보고(J10~J18). fencing 검사는 호출자가 먼저 한다. */
    public static Decision complete(JobFacts f, Completion c, String errorCode) {
        Objects.requireNonNull(c);
        switch (c) {
            case SUCCEEDED:
                // 취소 신호는 진행 상태를 덮지 않는다: 성공은 성공(STATE)
                return new Decision(JobStatus.SUCCEEDED, null, AttemptOutcome.SUCCEEDED, false, false, false);
            case FATAL:
                return new Decision(JobStatus.FAILED, reasonOr(errorCode, "FATAL_ERROR"), AttemptOutcome.FATAL_ERROR,
                        true, false, false); // J15

            case OUTCOME_UNKNOWN:
                if (!f.requiresOperation()) {
                    // 계약 위반: 외부 효과 없는 job은 결과 불명을 보고할 수 없다(A3 §4.1 불변조건)
                    return new Decision(JobStatus.FAILED, "OUTCOME_UNKNOWN_CONTRACT_VIOLATION",
                            AttemptOutcome.FATAL_ERROR, true, false, false);
                }
                return new Decision(JobStatus.RECOVERY_REQUIRED, "EXTERNAL_OUTCOME_UNKNOWN",
                        AttemptOutcome.OUTCOME_UNKNOWN, false, false, false);
            case CANCELLED:
                return new Decision(JobStatus.CANCELLED, "RUN_CANCELLED", AttemptOutcome.CANCELLED, false, true, false);
            case RETRYABLE:
            case ACTION_REQUIRED:
            default:
                return failedAttempt(f, c, errorCode,
                        c == Completion.RETRYABLE ? AttemptOutcome.RETRYABLE_ERROR : AttemptOutcome.ACTION_REQUIRED);
        }
    }

    /**
     * sweep의 lease/deadline 만료(J19/J20). INFLIGHT(허가 이후)면 RECOVERY_REQUIRED, 그 외는 재시도 가능한 실패로 처리한다.
     */
    public static Decision expired(JobFacts f, OperationPhase phase, boolean deadlineExceeded) {
        AttemptOutcome outcome = deadlineExceeded ? AttemptOutcome.DEADLINE_EXCEEDED : AttemptOutcome.LEASE_EXPIRED;
        if (phase == OperationPhase.INFLIGHT) {
            if (!f.requiresOperation()) {
                throw new IllegalArgumentException("INFLIGHT operation on a job without requires_operation");
            }
            return new Decision(JobStatus.RECOVERY_REQUIRED, "EXTERNAL_OUTCOME_UNKNOWN", outcome, false, false, false);
        }
        Decision d = failedAttempt(f, Completion.RETRYABLE, deadlineExceeded ? "DEADLINE_EXCEEDED" : "LEASE_EXPIRED",
                outcome);
        return new Decision(d.to(), d.reason(), outcome, d.failFast(), d.operationCancelRequest()
                || (phase == OperationPhase.PRE && d.to() == JobStatus.CANCELLED), d.retry());
    }

    /** RECOVERY_REQUIRED의 P12 해소(J22, J25~J27 및 최종 명세의 재발급 금지). */
    public static Decision resolve(JobFacts f, Resolution r) {
        if (r == Resolution.EXECUTED_RESULT_ADOPTED) {
            return new Decision(JobStatus.SUCCEEDED, null, null, false, false, false);
        }
        if (f.runCancelRequested()) {
            return new Decision(JobStatus.CANCELLED, "RUN_CANCELLED", null, false, false, false);
        }
        String reason = r == Resolution.NOT_EXECUTED ? "NOT_EXECUTED_AFTER_DISPATCH" : "EXTERNAL_EXECUTION_FAILED";
        return new Decision(JobStatus.FAILED, reason, null, true, false, false);
    }

    /** RETRYABLE/ACTION_REQUIRED 공통(J11~J14, J17, J18). */
    private static Decision failedAttempt(JobFacts f, Completion c, String errorCode, AttemptOutcome outcome) {
        if (f.runCancelRequested()) {
            return new Decision(JobStatus.CANCELLED, "RUN_CANCELLED", outcome, false, true, false); // J17
        }
        if (f.runFailFast()) {
            return new Decision(JobStatus.FAILED, "RUN_FAILED_FAST_NO_RETRY", outcome, false, false, false); // J18
        }
        if (c == Completion.ACTION_REQUIRED) {
            return new Decision(JobStatus.ACTION_REQUIRED, reasonOr(errorCode, "PREREQUISITE_NOT_MET"), outcome, false,
                    false, false); // J14: 자동 재시도 없음
        }
        if (f.autoLeft()) {
            return new Decision(JobStatus.RETRY_WAIT, reasonOr(errorCode, "RETRYABLE_ERROR"), outcome, false, false,
                    true); // J11
        }
        if (f.manualLeft()) {
            return new Decision(JobStatus.ACTION_REQUIRED, "AUTO_ATTEMPTS_EXHAUSTED", outcome, false, false, false); // J12
        }
        return new Decision(JobStatus.FAILED, "ATTEMPTS_EXHAUSTED", outcome, true, false, false); // J13
    }

    /** J21 수동 재개 가능 여부. 한도 초과는 422 POLICY_VIOLATION, 상태·Run 조건 위반은 409(호출자). */
    public static boolean canResume(JobStatus current, JobFacts f) {
        return current == JobStatus.ACTION_REQUIRED && f.manualLeft() && !f.runCancelRequested() && !f.runFailFast();
    }

    /** 선행 상태로 후행 WAITING job의 다음 상태(J02~J04). 미결이면 null. */
    public static JobStatus evaluateWaiting(java.util.Collection<JobStatus> predecessors, boolean runCancelRequested,
            boolean runFailFast) {
        if (predecessors.stream().anyMatch(s -> s == JobStatus.CANCELLED)) {
            return JobStatus.CANCELLED; // J04
        }
        if (predecessors.stream().anyMatch(s -> s == JobStatus.FAILED || s == JobStatus.SKIPPED)) {
            return JobStatus.SKIPPED; // J03
        }
        if (runCancelRequested) {
            return JobStatus.CANCELLED; // J05
        }
        if (runFailFast) {
            return JobStatus.SKIPPED; // J06
        }
        if (predecessors.stream().allMatch(s -> s == JobStatus.SUCCEEDED)) {
            return JobStatus.READY; // J02
        }
        return null;
    }

    /** R10 Run 종료 판정(모든 job 종료가 전제). 비용·operation 종료 확인은 호출자(T03-D 연동) 책임. */
    public static String runOutcome(java.util.Collection<JobStatus> jobs, boolean runCancelRequested) {
        if (jobs.stream().anyMatch(s -> !s.terminal())) {
            throw new IllegalArgumentException("all jobs must be terminal");
        }
        if (jobs.stream().allMatch(s -> s == JobStatus.SUCCEEDED)) {
            return "SUCCEEDED";
        }
        return runCancelRequested ? "CANCELLED" : "FAILED";
    }

    private static String reasonOr(String code, String fallback) {
        return code != null && code.matches("^[A-Z][A-Z0-9_]{1,63}$") ? code : fallback;
    }
}
