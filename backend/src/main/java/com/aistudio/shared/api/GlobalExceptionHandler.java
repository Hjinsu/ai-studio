package com.aistudio.shared.api;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 MVC 오류를 고정 코드와 공통 형식으로 변환한다 (ADR-011, ADR-012).
 * 내부 예외 메시지는 노출하지 않고 코드별 기본 메시지를 사용한다. 미분류 HTTP 오류는 실제 상태를 유지한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex) {
        ErrorCode code = ex.errorCode();
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return build(code, code.status(), ex.getMessage(), ex.details(), headers);
    }

    /** DB 유일성 위반이 서비스에서 변환되지 않고 올라온 경우의 안전망. */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<Object> handleDuplicateKey(org.springframework.dao.DuplicateKeyException ex) {
        log.warn("Unmapped duplicate key: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return build(ErrorCode.DUPLICATE_RESOURCE, ErrorCode.DUPLICATE_RESOURCE.status(), null, List.of(),
                new HttpHeaders());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorDetail> details = ex.getConstraintViolations().stream()
                .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.status(), null, details,
                new HttpHeaders());
    }

    /** 동시 수정 충돌(MariaDB 1020 스냅샷 충돌, 잠금 재시도 소진)은 500이 아니라 409로 응답한다. */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Object> handleDataAccess(org.springframework.dao.DataAccessException ex) {
        if (com.aistudio.shared.db.DatabaseErrors.isSnapshotConflict(ex)
                || ex instanceof org.springframework.dao.PessimisticLockingFailureException) {
            log.warn("Concurrent modification conflict: {}", ex.getClass().getSimpleName());
            return build(ErrorCode.RESOURCE_CONFLICT, ErrorCode.RESOURCE_CONFLICT.status(),
                    "The resource was changed concurrently. Retry the request.", List.of(), new HttpHeaders());
        }
        return handleUnexpected(ex);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.status(), null, List.of(),
                new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorDetail> details = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new ErrorDetail(
                        error instanceof FieldError fieldError ? fieldError.getField() : null,
                        error.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.status(), null, details, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorDetail> details = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorDetail(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.status(), null, details, headers);
    }

    /**
     * ResponseEntityExceptionHandler가 처리하는 표준 MVC 예외(404, 405, 406, 413, 415, 본문 파싱 오류 등)의 최종 변환 지점.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = ErrorCode.fromStatus(statusCode.value());
        if (statusCode.is5xxServerError()) {
            log.error("MVC infrastructure error", ex);
        }
        return build(code, statusCode, null, List.of(), headers);
    }

    private static ResponseEntity<Object> build(ErrorCode code, HttpStatusCode status, String message,
            List<ErrorDetail> details, HttpHeaders headers) {
        return ErrorResponses.build(code, status, message, details, TraceIdFilter.currentTraceId(), headers);
    }
}
