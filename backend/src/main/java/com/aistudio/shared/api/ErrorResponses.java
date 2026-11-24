package com.aistudio.shared.api;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 공통 오류 응답 생성. 내부 예외 메시지는 넣지 않는다 (ADR-011, ADR-012).
 */
final class ErrorResponses {

    private ErrorResponses() {
    }

    /**
     * Content-Type을 JSON으로 고정해 Accept 협상 실패(406) 때도 본문을 쓴다.
     */
    static ResponseEntity<Object> build(ErrorCode code, HttpStatusCode status, String message,
            List<ErrorDetail> details, String traceId, HttpHeaders headers) {
        ErrorResponse body = new ErrorResponse(
                code.name(),
                message != null ? message : code.defaultMessage(),
                traceId,
                Instant.now(),
                details);
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
