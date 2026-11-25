package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.aistudio.ApiTestSupport;
import com.aistudio.TestDatabases;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 세션 인증 흐름 (ADR-037, 044, 045, 051, 060): 세션 ID 교체, CSRF 재발급, 계정 존재 비노출, 로그인 제한,
 * 비밀번호 재설정 후 세션 무효화, 401/403 공통 형식, 실제 Tomcat 쿠키 속성.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAccountService accounts;

    @Autowired
    private LoginAdmission admission;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${server.servlet.session.timeout}")
    private Duration sessionTimeout;

    @LocalServerPort
    private int port;

    @BeforeEach
    void ensureAdmin() {
        ApiTestSupport.admin(accounts, jdbc);
    }

    private String csrf(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andExpect(ApiTestSupport.matchesContract())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("token").asString();
    }

    private MockHttpServletRequestBuilder login(MockHttpSession session, String token, String login, String password,
            String ip) {
        return post("/api/v1/auth/login").session(session)
                .header(SecurityConfiguration.CSRF_HEADER, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + login + "\",\"password\":\"" + password + "\"}")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    @Test
    void concurrentAdmissionUsesExistingHttp429ContractAndAllowsLoginAfterRelease() throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String ip = "198.51.100.12";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var held = executor.submit(() -> admission.execute("admin", ip, () -> {
                acquired.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                MockHttpSession session = new MockHttpSession();
                mockMvc.perform(login(session, csrf(session), "admin", ApiTestSupport.PASSWORD, ip))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                        .andExpect(ApiTestSupport.matchesContract());
                assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
            } finally {
                release.countDown();
            }
            held.get(10, TimeUnit.SECONDS);
        }
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(login(session, csrf(session), "admin", ApiTestSupport.PASSWORD, ip))
                .andExpect(status().isOk())
                .andExpect(ApiTestSupport.matchesContract());
    }

    @Test
    void lastLoginWriteFailureDoesNotAuthenticateSession() throws Exception {
        assertLoginWriteFailure("BEFORE UPDATE ON admin_user");
    }

    @Test
    void auditWriteFailureDoesNotAuthenticateSessionOrCommitLastLogin() throws Exception {
        var before = jdbc.queryForObject("SELECT last_login_at FROM admin_user WHERE login = 'admin'",
                java.sql.Timestamp.class);
        assertLoginWriteFailure("BEFORE INSERT ON audit_event");
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM admin_user WHERE login = 'admin'",
                java.sql.Timestamp.class)).isEqualTo(before);
    }

    private void assertLoginWriteFailure(String triggerTarget) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrf(session);
        jdbc.execute("CREATE TRIGGER fail_login_write " + triggerTarget
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test login write failure'");
        try {
            mockMvc.perform(login(session, token, "admin", ApiTestSupport.PASSWORD, "10.1.1.1"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
            assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                    .isNull();
            if (session.isInvalid()) {
                mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
            } else {
                assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNull();
                mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
            }
        } finally {
            jdbc.execute("DROP TRIGGER fail_login_write");
        }
    }

    @Test
    void loginWithoutCsrfIsRejectedWithCommonFormat() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void protectedApiWithoutSessionIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(ApiTestSupport.matchesContract());
    }

    @Test
    void loginRotatesSessionIdAndInvalidatesOldCsrfToken() throws Exception {
        // 세션 ID 교체 자체는 실제 Tomcat 테스트(realServerSessionCookieAttributesAndRotation)에서 확인한다
        MockHttpSession session = new MockHttpSession();
        String oldToken = csrf(session);

        mockMvc.perform(login(session, oldToken, "admin", ApiTestSupport.PASSWORD, "10.1.0.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(ApiTestSupport.matchesContract());

        // 로그인 전 CSRF 토큰은 더 이상 유효하지 않다
        mockMvc.perform(post("/api/v1/auth/logout").session(session).header(SecurityConfiguration.CSRF_HEADER, oldToken))
                .andExpect(status().isForbidden());
        String newToken = csrf(session);
        assertThat(newToken).isNotEqualTo(oldToken);

        // 저장된 SecurityContext로 다음 요청이 인증된다
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"))
                .andExpect(ApiTestSupport.matchesContract());

        mockMvc.perform(post("/api/v1/auth/logout").session(session).header(SecurityConfiguration.CSRF_HEADER, newToken))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).as("로그아웃 시 세션 폐기").isTrue();
    }

    @Test
    void unknownAccountAndWrongPasswordAreIndistinguishable() throws Exception {
        MockHttpSession first = new MockHttpSession();
        String unknown = mockMvc.perform(login(first, csrf(first), "nobody", "wrong-password-123", "10.2.0.1"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        MockHttpSession second = new MockHttpSession();
        String wrong = mockMvc.perform(login(second, csrf(second), "admin", "wrong-password-123", "10.2.0.2"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        JsonNode a = JSON.readTree(unknown);
        JsonNode b = JSON.readTree(wrong);
        assertThat(a.get("code").asString()).isEqualTo("UNAUTHORIZED").isEqualTo(b.get("code").asString());
        assertThat(a.get("message").asString()).isEqualTo(b.get("message").asString());
        String audit = jdbc.queryForObject("SELECT details_json FROM audit_event WHERE action = 'AUTH_LOGIN'"
                + " AND outcome = 'FAILURE' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(audit).doesNotContain("wrong-password-123");
    }

    @Test
    void loginIsRateLimitedPerLoginAndIpWithSingleAuditEntry() throws Exception {
        String ip = "10.3.0.1";
        for (int i = 0; i < 5; i++) {
            MockHttpSession session = new MockHttpSession();
            mockMvc.perform(login(session, csrf(session), "ratelimited", "wrong-password-123", ip))
                    .andExpect(status().isUnauthorized());
        }
        for (int i = 0; i < 3; i++) {
            MockHttpSession session = new MockHttpSession();
            mockMvc.perform(login(session, csrf(session), "ratelimited", "wrong-password-123", ip))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(ApiTestSupport.matchesContract());
        }
        Integer rateLimitedAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action ="
                + " 'AUTH_RATE_LIMITED' AND details_json LIKE '%ratelimited%'", Integer.class);
        assertThat(rateLimitedAudits).as("차단 창마다 1건만 기록").isEqualTo(1);
    }

    @Test
    void blockedLoginAndIpRejectsEvenCorrectPasswordButOtherIpWorks() throws Exception {
        String ip = "10.4.0.1";
        for (int i = 0; i < 5; i++) {
            MockHttpSession session = new MockHttpSession();
            mockMvc.perform(login(session, csrf(session), "admin", "wrong-password-123", ip));
        }
        MockHttpSession blocked = new MockHttpSession();
        mockMvc.perform(login(blocked, csrf(blocked), "admin", ApiTestSupport.PASSWORD, ip))
                .andExpect(status().isTooManyRequests());
        MockHttpSession other = new MockHttpSession();
        mockMvc.perform(login(other, csrf(other), "admin", ApiTestSupport.PASSWORD, "10.4.0.2"))
                .andExpect(status().isOk());
    }

    @Test
    void passwordResetInvalidatesExistingSessions() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(login(session, csrf(session), "admin", ApiTestSupport.PASSWORD, "10.5.0.1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());

        String newPassword = "another-strong-password-9";
        accounts.resetPassword("admin", newPassword.toCharArray());
        try {
            mockMvc.perform(get("/api/v1/auth/me").session(session))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            MockHttpSession fresh = new MockHttpSession();
            mockMvc.perform(login(fresh, csrf(fresh), "admin", newPassword, "10.5.0.2")).andExpect(status().isOk());
        } finally {
            accounts.resetPassword("admin", ApiTestSupport.PASSWORD.toCharArray());
        }
    }

    @Test
    void sessionTimeoutIsThirtyMinutes() {
        assertThat(sessionTimeout).isEqualTo(Duration.ofMinutes(30));
    }

    /** 실제 Tomcat: 쿠키 속성과 로그인 시 세션 쿠키 교체를 HTTP 수준에서 확인한다. */
    @Test
    void realServerSessionCookieAttributesAndRotation() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String base = "http://127.0.0.1:" + port;
        HttpResponse<String> csrfResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        List<String> setCookies = csrfResponse.headers().allValues("Set-Cookie");
        assertThat(setCookies).anySatisfy(cookie -> assertThat(cookie)
                .startsWith("AI_STUDIO_SESSION=")
                .containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax")
                .contains("Path=/")
                .doesNotContainIgnoringCase("Secure"));
        String firstSession = sessionValue(setCookies);
        String token = JSON.readTree(csrfResponse.body()).get("token").asString();

        HttpResponse<String> loginResponse = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header(SecurityConfiguration.CSRF_HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"login\":\"admin\",\"password\":\"" + ApiTestSupport.PASSWORD + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        assertThat(sessionValue(loginResponse.headers().allValues("Set-Cookie")))
                .as("로그인 후 세션 쿠키 값 교체").isNotNull().isNotEqualTo(firstSession);

        HttpResponse<String> me = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
    }

    private static String sessionValue(List<String> setCookies) {
        return setCookies.stream()
                .filter(cookie -> cookie.startsWith("AI_STUDIO_SESSION="))
                .map(cookie -> cookie.substring("AI_STUDIO_SESSION=".length(), cookie.indexOf(';')))
                .findFirst().orElse(null);
    }
}
