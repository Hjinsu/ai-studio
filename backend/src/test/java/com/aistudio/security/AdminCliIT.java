package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aistudio.AiStudioApplication;
import com.aistudio.TestDatabases;

/**
 * 관리자 CLI 실제 Spring 생성 경로 회귀 테스트(2026-11-25 결함: AdminCommandRunner 생성자 선택 실패).
 * main과 같은 {@link AiStudioApplication#adminCli()} 구성으로 비웹 컨텍스트를 띄워 Bean 생성·웹 서버 미실행·
 * create/reset-password·콘솔 없음 거부를 검증한다. 숨김 입력은 테스트 컨텍스트에만 PasswordPrompt Bean으로 대체한다.
 */
@Tag("integration")
class AdminCliIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    private static final String PASSWORD = "correct-horse-battery-1";
    private static final String NEW_PASSWORD = "another-horse-battery-2";

    /** 테스트 DB 접속 정보를 최우선 속성 소스로 둔다(빌더 기본 속성은 profile yml보다 우선순위가 낮다). */
    private static void database(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("adminCliTestDatabase", Map.of(
                "spring.datasource.url", DB.jdbcUrl(),
                "spring.datasource.username", DB.server().user(),
                "spring.datasource.password", DB.server().password(),
                "ai-studio.datasource.expected-database", DB.name())));
    }

    /** 실행 결과: 종료 코드와 DB 확인용 템플릿(컨텍스트는 닫힘). */
    private record Result(int exitCode, ConfigurableApplicationContext context) {
    }

    private Result run(AdminCommandRunner.PasswordPrompt prompt, String... args) {
        var builder = AiStudioApplication.adminCli().profiles("test").initializers(AdminCliIT::database);
        if (prompt != null) {
            builder.initializers(context -> ((ConfigurableApplicationContext) context).getBeanFactory()
                    .registerSingleton("testPasswordPrompt", prompt));
        }
        ConfigurableApplicationContext context = builder.run(args);
        // 비웹 모드: 웹 서버 컨텍스트가 아니고 CLI Bean이 생성됨
        assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
        assertThat(context.getBeansOfType(AdminCommandRunner.class)).hasSize(1);
        int code = SpringApplication.exit(context);
        return new Result(code, context);
    }

    private static AdminCommandRunner.PasswordPrompt answers(String... values) {
        Deque<String> queue = new ArrayDeque<>(List.of(values));
        return text -> queue.isEmpty() ? null : queue.pop().toCharArray();
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(DB.dataSource());
    }

    @Test
    void createAndResetPasswordRunThroughTheRealSpringBeanPath() {
        // 콘솔 없음(PasswordPrompt Bean 없음 → System.console(), 테스트 JVM은 null) → 거부, 기록 없음
        assertThat(run(null, "admin", "create", "--login", "admin").exitCode()).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class)).isZero();

        // 확인 불일치 → 거부
        assertThat(run(answers(PASSWORD, "different-password-3"), "admin", "create", "--login", "admin").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class)).isZero();

        // create 성공: 해시 저장(평문 아님), bootstrap 기록
        assertThat(run(answers(PASSWORD, PASSWORD), "admin", "create", "--login", "admin").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_OK);
        String hash = jdbc().queryForObject("SELECT password_hash FROM admin_user WHERE login = 'admin'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM admin_bootstrap", Integer.class)).isEqualTo(1);

        // 두 번째 create는 거부(최초 1회만)
        assertThat(run(answers(PASSWORD, PASSWORD), "admin", "create", "--login", "other").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class)).isEqualTo(1);

        // reset-password 성공: 해시 변경, credential_version 증가(기존 세션 무효화)
        int before = jdbc().queryForObject("SELECT credential_version FROM admin_user WHERE login = 'admin'", Integer.class);
        assertThat(run(answers(NEW_PASSWORD, NEW_PASSWORD), "admin", "reset-password", "--login=admin").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_OK);
        assertThat(jdbc().queryForObject("SELECT password_hash FROM admin_user WHERE login = 'admin'", String.class))
                .isNotEqualTo(hash);
        assertThat(jdbc().queryForObject("SELECT credential_version FROM admin_user WHERE login = 'admin'", Integer.class))
                .isEqualTo(before + 1);

        // 없는 로그인 reset → 거부, 약한 비밀번호 → 거부(정책 유지)
        assertThat(run(answers(NEW_PASSWORD, NEW_PASSWORD), "admin", "reset-password", "--login", "nobody").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(run(answers("short", "short"), "admin", "reset-password", "--login", "admin").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_REJECTED);

        // 잘못된 사용법 → 거부
        assertThat(run(answers(), "admin", "delete", "--login", "admin").exitCode())
                .isEqualTo(AdminCommandRunner.EXIT_REJECTED);
    }
}
