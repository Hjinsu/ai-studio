package com.aistudio;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * 통합 테스트용 격리 MariaDB (ADR-024). 테스트 클래스마다 새 DB(`ai_studio_test_*`)를 만든다.
 * - 기본: Testcontainers(이미지 버전은 infra/docker-compose.yml, docs/16_SOURCES.md와 동일)
 * - Docker가 없는 로컬: 환경변수 AI_STUDIO_IT_DB_HOST/PORT/USER/PASSWORD로 지정한 **별도 임시 MariaDB 서버**.
 *   실제 작업 DB 서버를 지정하지 않는다(테스트가 DB·계정을 생성한다).
 * 서버 collation은 로컬 ai_studio 생성 조건(utf8mb4_unicode_ci)과 맞춘다.
 */
public final class TestDatabases {

    static final String IMAGE = "mariadb:11.8.8";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static Server server;

    private TestDatabases() {
    }

    public record Server(String host, int port, String user, String password) {
        String jdbcUrl(String database) {
            return "jdbc:mariadb://" + host + ":" + port + "/" + database;
        }

        public Connection adminConnection() throws SQLException {
            return DriverManager.getConnection(jdbcUrl(""), user, password);
        }
    }

    public record TestDatabase(Server server, String name) {

        public String jdbcUrl() {
            return server.jdbcUrl(name);
        }

        public DataSource dataSource() {
            return dataSource(server.user(), server.password());
        }

        public DataSource dataSource(String user, String password) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl(), user, password);
            dataSource.setDriverClassName("org.mariadb.jdbc.Driver");
            return dataSource;
        }

        /** Spring 테스트 컨텍스트가 이 DB에 접속하고 허용 DB 검증도 이 이름으로 하도록 등록한다. */
        public void register(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", this::jdbcUrl);
            registry.add("spring.datasource.username", server::user);
            registry.add("spring.datasource.password", server::password);
            registry.add("ai-studio.datasource.expected-database", this::name);
        }
    }

    public static synchronized Server server() {
        if (server == null) {
            String host = System.getenv("AI_STUDIO_IT_DB_HOST");
            if (host != null && !host.isBlank()) {
                server = new Server(host, Integer.parseInt(System.getenv().getOrDefault("AI_STUDIO_IT_DB_PORT", "3306")),
                        System.getenv().getOrDefault("AI_STUDIO_IT_DB_USER", "root"),
                        System.getenv("AI_STUDIO_IT_DB_PASSWORD"));
            } else {
                MariaDBContainer container = new MariaDBContainer(IMAGE)
                        .withUsername("root")
                        .withPassword("test")
                        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                                "--default-time-zone=+00:00", "--max-connections=300");
                container.start();
                server = new Server(container.getHost(), container.getFirstMappedPort(), "root", "test");
            }
        }
        return server;
    }

    /** 새 격리 DB를 만든다. 이름은 항상 `ai_studio_test_` 접두사. */
    public static TestDatabase create() {
        Server current = server();
        String name = "ai_studio_test_" + ProcessHandle.current().pid() + "_" + SEQUENCE.incrementAndGet();
        try (Connection connection = current.adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 DB 생성 실패: " + name, e);
        }
        return new TestDatabase(current, name);
    }
}
