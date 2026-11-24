package com.aistudio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 서블릿 컨테이너에서 필터 단계 오류(향후 Spring Security 필터 포함)가 /error를 거쳐 공통 형식으로 응답되는지 검증한다 (ADR-024).
 * DB 없이 실행한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@ActiveProfiles("test")
class ErrorDispatchTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @CsvSource({"/filter-test/unauthorized, 401, UNAUTHORIZED", "/filter-test/forbidden, 403, FORBIDDEN",
            "/filter-test/teapot, 418, CLIENT_ERROR"})
    void sendErrorFromFilterUsesCommonFormat(String path, int status, String code) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("traceId").asString())
                .isEqualTo(response.headers().firstValue("X-Trace-Id").orElseThrow());
    }

    @Test
    void exceptionFromFilterDoesNotLeakMessage() throws Exception {
        HttpResponse<String> response = get("/filter-test/boom");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("secret filter detail");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(body.get("timestamp").asString()).endsWith("Z");
    }

    @Test
    void unknownPathUsesCommonFormat() throws Exception {
        HttpResponse<String> response = get("/api/v1/unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @TestConfiguration
    static class FailingFilterConfiguration {

        @Bean
        OncePerRequestFilter failingTestFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    switch (request.getRequestURI()) {
                        case "/filter-test/unauthorized" -> response.sendError(401);
                        case "/filter-test/forbidden" -> response.sendError(403);
                        case "/filter-test/teapot" -> response.sendError(418);
                        case "/filter-test/boom" -> throw new IllegalStateException("secret filter detail");
                        default -> chain.doFilter(request, response);
                    }
                }
            };
        }
    }
}
