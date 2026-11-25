package com.aistudio.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    @Test
    void exponentialWithCap() {
        assertThat(RetryBackoff.baseDelayMillis(1)).isEqualTo(5_000);
        assertThat(RetryBackoff.baseDelayMillis(2)).isEqualTo(10_000);
        assertThat(RetryBackoff.baseDelayMillis(3)).isEqualTo(20_000);
        assertThat(RetryBackoff.baseDelayMillis(7)).isEqualTo(300_000);
        assertThat(RetryBackoff.baseDelayMillis(40)).isEqualTo(300_000);
    }

    @Test
    void equalJitterStaysWithinHalfAndFull() {
        assertThat(RetryBackoff.delayMillis(2, null, bound -> 0)).isEqualTo(5_000);
        assertThat(RetryBackoff.delayMillis(2, null, bound -> bound - 1)).isEqualTo(10_000);
    }

    @Test
    void retryAfterLongerThanBackoffWinsAndTooLongIsRejected() {
        assertThat(RetryBackoff.delayMillis(1, 60_000L, bound -> 0)).isEqualTo(60_000);
        assertThat(RetryBackoff.delayMillis(1, 1_000L, bound -> 0)).isEqualTo(2_500);
        assertThat(RetryBackoff.delayMillis(1, 3_600_001L, bound -> 0)).isEqualTo(-1);
    }
}
