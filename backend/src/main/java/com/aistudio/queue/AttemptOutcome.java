package com.aistudio.queue;

/** job_attempt.outcome (V7 ck_attempt_outcome). */
public enum AttemptOutcome {
    SUCCEEDED, RETRYABLE_ERROR, ACTION_REQUIRED, FATAL_ERROR, OUTCOME_UNKNOWN, LEASE_EXPIRED, DEADLINE_EXCEEDED,
    CANCELLED, SUPERSEDED
}
