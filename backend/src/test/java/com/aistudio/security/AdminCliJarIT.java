package com.aistudio.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aistudio.TestDatabases;

/**
 * 실제 bootJar로 관리자 CLI를 실행하는 회귀 테스트(2026-11-25 결함: `java -jar ... admin create`가
 * BeanInstantiationException으로 실패). 하위 프로세스는 콘솔이 없으므로, 정상이라면 Spring 컨텍스트가 비웹 모드로
 * 초기화되고 Flyway가 실행된 뒤 "콘솔 없음"으로 거부(종료 코드 2)되어야 한다. 웹 서버는 시작되지 않아야 한다.
 * jar 경로는 Gradle이 시스템 속성 ai-studio.boot-jar로 넘긴다(integrationTest는 bootJar에 의존).
 */
@Tag("integration")
class AdminCliJarIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    record Outcome(int exitCode, String output) {
    }

    private static Outcome runJar(Path workDir, String... cliArgs) throws IOException, InterruptedException {
        String jar = System.getProperty("ai-studio.boot-jar");
        assertThat(jar).as("system property ai-studio.boot-jar (set by Gradle)").isNotBlank();
        assertThat(Path.of(jar)).exists();
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>(List.of(java,
                "-Dspring.profiles.active=test",
                "-Dspring.datasource.url=" + DB.jdbcUrl(),
                "-Dspring.datasource.username=" + DB.server().user(),
                "-Dspring.datasource.password=" + DB.server().password(),
                "-Dai-studio.datasource.expected-database=" + DB.name(),
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-jar", jar));
        command.addAll(List.of(cliArgs));
        Path log = workDir.resolve("cli.log");
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE).start();
        process.getOutputStream().close(); // stdin 평문 입력 경로 없음
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("CLI must exit by itself").isTrue();
        return new Outcome(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
    }

    @Test
    void bootJarAdminCommandsStartNonWebContextAndRefuseWithoutConsole(@TempDir Path workDir) throws Exception {
        for (String command : List.of("create", "reset-password")) {
            Outcome outcome = runJar(workDir, "admin", command, "--login", "admin");
            assertThat(outcome.output()).as(command)
                    .doesNotContain("BeanCreationException", "BeanInstantiationException", "APPLICATION FAILED TO START")
                    .doesNotContain("Tomcat", "Netty", "Undertow", "web server")
                    .contains("콘솔이 없습니다");
            assertThat(outcome.exitCode()).as(command + "\n" + outcome.output()).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        }
        // 컨텍스트가 실제로 초기화되어 Flyway까지 실행됐고, 관리자는 만들어지지 않았다
        JdbcTemplate jdbc = new JdbcTemplate(DB.dataSource());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class)).isZero();

        Outcome usage = runJar(workDir, "admin", "create");
        assertThat(usage.exitCode()).isEqualTo(AdminCommandRunner.EXIT_REJECTED);
        assertThat(usage.output()).contains("사용법");
    }
}
