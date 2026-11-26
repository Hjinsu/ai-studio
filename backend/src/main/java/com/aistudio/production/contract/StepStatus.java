package com.aistudio.production.contract;

public enum StepStatus {
    PENDING, RUNNING, RETRY_WAIT, RECOVERY_REQUIRED, SUCCEEDED, FAILED, CANCELLED
}
