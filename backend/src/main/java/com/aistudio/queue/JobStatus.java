package com.aistudio.queue;

import java.util.EnumSet;
import java.util.Set;

/** automation_job.status (T03 STATE, V7 ck_job_status). */
public enum JobStatus {
    WAITING, READY, RUNNING, RETRY_WAIT, ACTION_REQUIRED, RECOVERY_REQUIRED, SUCCEEDED, FAILED, CANCELLED, SKIPPED;

    public static final Set<JobStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, CANCELLED, SKIPPED);
    /** claim 후보(J08). */
    public static final Set<JobStatus> CLAIMABLE = EnumSet.of(READY, RETRY_WAIT);
    /** Run 취소·fail-fast 시 즉시 종료되는 미실행 상태(J05/J06). */
    public static final Set<JobStatus> NOT_STARTED = EnumSet.of(WAITING, READY, RETRY_WAIT, ACTION_REQUIRED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
