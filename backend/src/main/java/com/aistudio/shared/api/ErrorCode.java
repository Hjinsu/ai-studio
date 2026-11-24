package com.aistudio.shared.api;

import org.springframework.http.HttpStatus;

/**
 * 고정 API 오류 코드 (ADR-011, ADR-012, docs/04_API_SPEC.md). 코드와 HTTP 상태는 변경하지 않는다.
 * CLIENT_ERROR, INTERNAL_SERVER_ERROR는 미분류 HTTP 오류에 쓰이며 응답 상태는 실제 상태를 유지한다.
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "The request is invalid."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request failed validation."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access is denied."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "The HTTP method is not allowed for this resource."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "The requested response format is not supported."),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state of the resource."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "The requested state transition is not allowed."),
    PREREQUISITE_NOT_MET(HttpStatus.CONFLICT, "A prerequisite for this request has not been met."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "The resource already exists."),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The request payload is too large."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The content type is not supported."),
    POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "The request violates a policy."),
    BUDGET_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "The request exceeds the budget limit."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests."),
    CLIENT_ERROR(HttpStatus.BAD_REQUEST, "The request could not be processed."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    /** 이 코드의 기본 HTTP 상태. 미분류 오류(CLIENT_ERROR 등)는 실제 상태로 응답한다. */
    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * HTTP 상태를 오류 코드로 변환한다. 400은 INVALID_REQUEST, 그 외 미분류 4xx는 CLIENT_ERROR,
     * 미분류 5xx는 INTERNAL_SERVER_ERROR. 422/409처럼 여러 코드가 쓰는 상태는 일반 코드로 변환한다.
     */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> RESOURCE_CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> POLICY_VIOLATION;
            case 429 -> RATE_LIMIT_EXCEEDED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 400 && status < 500 ? CLIENT_ERROR : INTERNAL_SERVER_ERROR;
        };
    }
}
