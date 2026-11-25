package com.aistudio.queue;

import java.util.function.LongUnaryOperator;

/**
 * 자동 재시도 지연(A3 §6.5): delay_n = min(300s, 5s × 2^(n−1)), equal jitter = delay_n/2 + rand(0, delay_n/2).
 * n은 실패한 자동 실행 순번(1부터). Retry-After가 더 길면 그 값을 쓰고 3600초를 넘으면 ACTION_REQUIRED
 * (RETRY_AFTER_TOO_LONG)로 처리한다. DB 시각에 더할 지연 길이(밀리초)만 계산하며 시각 자체는 DB가 정한다.
 */
public final class RetryBackoff {

    static final long BASE_MILLIS = 5_000;
    static final long CAP_MILLIS = 300_000;
    public static final long MAX_RETRY_AFTER_MILLIS = 3_600_000;

    private RetryBackoff() {
    }

    /** jitter 전 지연. */
    public static long baseDelayMillis(int failedAutoAttempt) {
        if (failedAutoAttempt < 1) {
            throw new IllegalArgumentException("failedAutoAttempt must be >= 1");
        }
        int shift = Math.min(failedAutoAttempt - 1, 20);
        return Math.min(CAP_MILLIS, BASE_MILLIS << shift);
    }

    /**
     * @param randomBelow 0 이상 인수 미만의 난수를 돌려주는 함수(테스트 주입용, 운영은 ThreadLocalRandom)
     * @param retryAfterMillis 실행기가 받은 Retry-After(없으면 null)
     * @return 지연 밀리초. Retry-After가 상한을 넘으면 -1(ACTION_REQUIRED RETRY_AFTER_TOO_LONG)
     */
    public static long delayMillis(int failedAutoAttempt, Long retryAfterMillis, LongUnaryOperator randomBelow) {
        long base = baseDelayMillis(failedAutoAttempt);
        long half = base / 2;
        long jittered = half + randomBelow.applyAsLong(half + 1);
        if (retryAfterMillis != null) {
            if (retryAfterMillis > MAX_RETRY_AFTER_MILLIS) {
                return -1;
            }
            return Math.max(jittered, retryAfterMillis);
        }
        return jittered;
    }
}
