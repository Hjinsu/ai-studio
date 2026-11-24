package com.aistudio.shared.api;

import java.util.List;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVC 밖(Servlet 필터, 향후 Spring Security 필터, sendError)에서 발생한 오류를 공통 형식으로 응답한다 (ADR-024).
 * Spring Boot 기본 BasicErrorController를 대체한다. 내부 예외 메시지는 노출하지 않는다.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<Object> error(HttpServletRequest request, HttpServletResponse response) {
        int status = resolveStatus(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        ErrorCode code = ErrorCode.fromStatus(status);
        // 오류 dispatch 시점에는 TraceIdFilter의 MDC가 정리되었으므로 이미 설정된 응답 헤더를 재사용한다
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        if (traceId == null) {
            traceId = TraceIdFilter.currentTraceId();
        }
        return ErrorResponses.build(code, HttpStatusCode.valueOf(status), null, List.of(), traceId,
                new HttpHeaders());
    }

    static int resolveStatus(Object attribute) {
        if (attribute instanceof Integer status && status >= 400 && status <= 599) {
            return status;
        }
        return 500;
    }
}
