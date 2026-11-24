package com.aistudio.shared.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ErrorTestController.class)
@ActiveProfiles("test")
@Import({GlobalExceptionHandlerTest.ErrorTestController.class, GlobalExceptionHandler.class, TraceIdFilter.class})
class GlobalExceptionHandlerTest {

    private static final String TIMESTAMP_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownPathReturnsResourceNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.defaultMessage()))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").value(org.hamcrest.Matchers.matchesPattern(TIMESTAMP_UTC)))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void wrongMethodReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/test/ok").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().exists("Allow"));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/test/echo").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void notAcceptableReturns406WithJsonBody() throws Exception {
        mockMvc.perform(get("/test/json-only").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void uploadTooLargeReturns413() throws Exception {
        mockMvc.perform(get("/test/too-large"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void malformedJsonReturnsInvalidRequestWithoutParserDetails() throws Exception {
        mockMvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.defaultMessage()));
    }

    @Test
    void invalidBodyReturnsValidationFailedWithFieldDetails() throws Exception {
        mockMvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("name"))
                .andExpect(jsonPath("$.details[0].reason").isNotEmpty());
    }

    @Test
    void typeMismatchReturnsInvalidRequest() throws Exception {
        mockMvc.perform(get("/test/items/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // 미분류 HTTP 오류는 실제 상태를 유지한다 (ADR-012)
    @ParameterizedTest
    @CsvSource({"418, CLIENT_ERROR", "451, CLIENT_ERROR", "502, INTERNAL_SERVER_ERROR",
            "504, INTERNAL_SERVER_ERROR", "503, SERVICE_UNAVAILABLE"})
    void unclassifiedStatusKeepsActualStatus(int status, String code) throws Exception {
        mockMvc.perform(get("/test/status/" + status))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(ErrorCode.valueOf(code).defaultMessage()));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void apiExceptionMapsToFixedCodeAndStatus(ErrorCode code) throws Exception {
        mockMvc.perform(get("/test/api-error/" + code.name()))
                .andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value("custom message"))
                .andExpect(jsonPath("$.details[0].field").value("f"));
    }

    @Test
    void unexpectedExceptionDoesNotLeakInternalMessage() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_SERVER_ERROR.defaultMessage()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret internal detail"))));
    }

    @Test
    void responseStatusReasonIsNotExposed() throws Exception {
        mockMvc.perform(get("/test/status/418"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal reason"))));
    }

    @Test
    void traceIdIsEchoedInHeaderAndBody() throws Exception {
        mockMvc.perform(get("/test/boom").header(TraceIdFilter.HEADER, "trace-abc-12345"))
                .andExpect(header().string(TraceIdFilter.HEADER, "trace-abc-12345"))
                .andExpect(jsonPath("$.traceId").value("trace-abc-12345"));
    }

    @RestController
    static class ErrorTestController {

        record EchoRequest(@NotBlank String name) {
        }

        @GetMapping("/test/ok")
        String ok() {
            return "ok";
        }

        @GetMapping(path = "/test/json-only", produces = MediaType.APPLICATION_JSON_VALUE)
        EchoRequest jsonOnly() {
            return new EchoRequest("x");
        }

        @GetMapping("/test/too-large")
        String tooLarge() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @PostMapping(path = "/test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
        EchoRequest echo(@Valid @RequestBody EchoRequest request) {
            return request;
        }

        @GetMapping("/test/items/{id}")
        long item(@PathVariable long id) {
            return id;
        }

        @GetMapping("/test/status/{status}")
        String status(@PathVariable int status) {
            throw new ResponseStatusException(HttpStatus.valueOf(status), "internal reason");
        }

        @GetMapping("/test/api-error/{code}")
        String apiError(@PathVariable ErrorCode code) {
            throw new ApiException(code, "custom message", List.of(new ErrorDetail("f", "r")));
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
