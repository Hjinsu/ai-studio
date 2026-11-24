package com.aistudio.shared.api;

import java.time.Instant;
import java.util.List;

/**
 * 공통 오류 응답 {code,message,traceId,timestamp,details} (ADR-011).
 */
public record ErrorResponse(String code, String message, String traceId, Instant timestamp, List<ErrorDetail> details) {

    public ErrorResponse {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
