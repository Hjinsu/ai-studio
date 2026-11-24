package com.aistudio.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorCodeTest {

    @Test
    void codesAndStatusesMatchApprovedContract() {
        // ADR-011 기본 11개 + ADR-012 추가 8개
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("INVALID_REQUEST", 400);
        expected.put("VALIDATION_FAILED", 400);
        expected.put("UNAUTHORIZED", 401);
        expected.put("FORBIDDEN", 403);
        expected.put("RESOURCE_NOT_FOUND", 404);
        expected.put("METHOD_NOT_ALLOWED", 405);
        expected.put("NOT_ACCEPTABLE", 406);
        expected.put("RESOURCE_CONFLICT", 409);
        expected.put("INVALID_STATE_TRANSITION", 409);
        expected.put("PREREQUISITE_NOT_MET", 409);
        expected.put("DUPLICATE_RESOURCE", 409);
        expected.put("PAYLOAD_TOO_LARGE", 413);
        expected.put("UNSUPPORTED_MEDIA_TYPE", 415);
        expected.put("POLICY_VIOLATION", 422);
        expected.put("BUDGET_EXCEEDED", 422);
        expected.put("RATE_LIMIT_EXCEEDED", 429);
        expected.put("CLIENT_ERROR", 400);
        expected.put("INTERNAL_SERVER_ERROR", 500);
        expected.put("SERVICE_UNAVAILABLE", 503);

        Map<String, Integer> actual = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(Enum::name, c -> c.status().value(), (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsExactlyEntriesOf(expected);
    }

    @ParameterizedTest
    @CsvSource({"400, INVALID_REQUEST", "401, UNAUTHORIZED", "403, FORBIDDEN", "404, RESOURCE_NOT_FOUND",
            "405, METHOD_NOT_ALLOWED", "406, NOT_ACCEPTABLE", "409, RESOURCE_CONFLICT", "413, PAYLOAD_TOO_LARGE",
            "415, UNSUPPORTED_MEDIA_TYPE", "422, POLICY_VIOLATION", "429, RATE_LIMIT_EXCEEDED",
            "418, CLIENT_ERROR", "410, CLIENT_ERROR", "500, INTERNAL_SERVER_ERROR", "502, INTERNAL_SERVER_ERROR",
            "503, SERVICE_UNAVAILABLE", "504, INTERNAL_SERVER_ERROR"})
    void mapsHttpStatusToCode(int status, ErrorCode expected) {
        assertThat(ErrorCode.fromStatus(status)).isEqualTo(expected);
    }
}
