package com.aistudio.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceIdFilterTest {

    @Test
    void reusesSafeClientTraceId() {
        assertThat(TraceIdFilter.resolveTraceId("client-trace-0001")).isEqualTo("client-trace-0001");
    }

    @Test
    void replacesMissingOrUnsafeTraceId() {
        assertThat(TraceIdFilter.resolveTraceId(null)).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolveTraceId("short")).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolveTraceId("bad\r\nheader-injection")).matches("[0-9a-f]{32}");
        assertThat(TraceIdFilter.resolveTraceId("x".repeat(65))).matches("[0-9a-f]{32}");
    }
}
