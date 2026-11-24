package com.aistudio.shared.api;

import java.util.List;

/**
 * 고정 오류 코드로 응답해야 하는 애플리케이션 예외. message는 클라이언트에 그대로 노출되므로 비밀정보를 넣지 않는다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient List<ErrorDetail> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<ErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<ErrorDetail> details() {
        return details;
    }
}
