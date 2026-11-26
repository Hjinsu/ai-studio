package com.aistudio.production.execution;

/**
 * Failure of a local, effect-free step. Retryable failures are retried with backoff up to the attempt limit.
 * Effect steps never use this path: port failures are treated as unknown outcomes.
 */
public class StepFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public StepFailure(String code, boolean retryable, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid step error code");
        }
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
