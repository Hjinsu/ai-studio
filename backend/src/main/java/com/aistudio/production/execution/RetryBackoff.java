package com.aistudio.production.execution;

import java.util.function.LongUnaryOperator;

/**
 * Delay before the next automatic attempt of a local, effect-free step (selected from T03-C RetryBackoff).
 * delay_n = min(cap, base x 2^(n-1)) with equal jitter: delay_n/2 + rand(0, delay_n/2). n is the failed attempt
 * number starting at 1. The executor adds the delay to the DB UTC clock; it never reads the JVM clock.
 */
final class RetryBackoff {
    private RetryBackoff() {}

    static long baseDelayMillis(int failedAttempt, long baseMillis, long capMillis) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be >= 1");
        }
        int shift = Math.min(failedAttempt - 1, 20);
        long delay = baseMillis << shift;
        return delay < 0 ? capMillis : Math.min(capMillis, delay);
    }

    /** @param randomBelow returns a value in [0, bound) — injected for tests */
    static long delayMillis(int failedAttempt, long baseMillis, long capMillis, LongUnaryOperator randomBelow) {
        long base = baseDelayMillis(failedAttempt, baseMillis, capMillis);
        long half = base / 2;
        return half + randomBelow.applyAsLong(half + 1);
    }
}
