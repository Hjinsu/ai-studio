package com.aistudio.shared.api;

import java.util.List;

/**
 * 고정 오류 코드로 응답해야 하는 애플리케이션 예외. message는 클라이언트에 그대로 노출되므로 비밀정보를 넣지 않는다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<ErrorDetail> details;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        this(errorCode, message, details, null);
    }

    private ApiException(ErrorCode errorCode, String message, List<ErrorDetail> details, Long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 429 응답에 Retry-After 헤더를 붙인다. */
    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, ErrorCode.RATE_LIMIT_EXCEEDED.defaultMessage(), List.of(),
                Math.max(1, retryAfterSeconds));
    }

    public static ApiException validation(String field, String reason) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ErrorDetail(field, reason)));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
