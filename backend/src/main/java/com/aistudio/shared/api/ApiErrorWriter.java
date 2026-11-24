package com.aistudio.shared.api;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * MVC 밖(Spring Security 필터 등)에서 공통 오류 형식을 직접 쓰는 도구 (ADR-024).
 */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        ErrorResponse body = new ErrorResponse(code.name(), code.defaultMessage(),
                traceId != null ? traceId : TraceIdFilter.currentTraceId(), Instant.now(), List.of());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
