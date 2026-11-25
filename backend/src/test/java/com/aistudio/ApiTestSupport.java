package com.aistudio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.aistudio.security.AdminAccountService;
import com.aistudio.security.AdminPrincipal;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;

/**
 * API 통합 테스트 공통: 관리자 생성, 인증된 요청, OpenAPI 계약 검증(ADR-032, ADR-041).
 */
public final class ApiTestSupport {

    public static final String PASSWORD = "correct-horse-battery-1";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static OpenApiInteractionValidator validator;

    private ApiTestSupport() {
    }

    /** 최초 관리자(또는 이미 있으면 기존 관리자)를 만들고 인증 주체를 반환한다. */
    public static AdminPrincipal admin(AdminAccountService accounts, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        Long existing = jdbc.query("SELECT id FROM admin_user ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        long id = existing != null ? existing : accounts.createInitialAdmin("admin", PASSWORD.toCharArray());
        Integer credentialVersion = jdbc.queryForObject("SELECT credential_version FROM admin_user WHERE id = ?",
                Integer.class, id);
        String login = jdbc.queryForObject("SELECT login FROM admin_user WHERE id = ?", String.class, id);
        return new AdminPrincipal(id, login, credentialVersion);
    }

    /** 세션 인증 + CSRF 토큰을 붙인 요청(실제 세션 흐름은 AuthIT에서 별도 검증). */
    public static RequestPostProcessor as(AdminPrincipal principal) {
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        RequestPostProcessor auth = authentication(token);
        RequestPostProcessor csrf = csrf().asHeader();
        return request -> csrf.postProcessRequest(auth.postProcessRequest(request));
    }

    public static String uniqueKey(String prefix) {
        return prefix + "-" + System.nanoTime() + "-" + SEQUENCE.incrementAndGet();
    }

    /** docs/api/openapi.yaml 대비 요청·응답 검증. 보안 스키마(쿠키·CSRF)는 MockMvc 특성상 검사하지 않는다. */
    public static ResultMatcher matchesContract() {
        return OpenApiValidationMatchers.openApi().isValid(validator());
    }

    /**
     * 원본 docs/api/openapi.yaml을 변형 없이 사용한다(P-T02-01: 헬퍼가 명세 오류를 숨기지 않도록).
     * 검증기는 additionalProperties가 없는 스키마에 false를 주입하므로 allOf 조합은 명세에
     * {@code unevaluatedProperties: false}를 명시해야 한다(OpenApiContractTest가 규칙과 동작을 검사).
     */
    public static synchronized OpenApiInteractionValidator validator() {
        if (validator == null) {
            try {
                String spec = Files.readString(Path.of("..", "docs", "api", "openapi.yaml"), StandardCharsets.UTF_8);
                validator = OpenApiInteractionValidator.createForInlineApiSpecification(spec)
                        .withLevelResolver(LevelResolver.create()
                                .withLevel("validation.request.security", ValidationReport.Level.IGNORE)
                                .build())
                        .build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return validator;
    }
}
