package com.aistudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * 신규 DB 초기화: 빈 격리 DB에 애플리케이션 기동(Flyway V1→V2) 후 DB 상태와 health를 검증한다. Docker 필요 (ADR-011).
 * Docker가 없으면 실패한다. 미실행을 통과로 숨기지 않기 위해 disabledWithoutDocker를 쓰지 않는다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseBootstrapIT {

    @Container
    @ServiceConnection
    static final MariaDBContainer MARIADB = TestDatabases.isolatedDatabase("ai_studio_test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allMigrationsAreAppliedOnCleanDatabase() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void connectedToIsolatedTestDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("ai_studio_test");
    }

    @Test
    void noDomainTablesExistInT01() {
        Integer otherTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                Integer.class);
        assertThat(otherTables).isZero();
    }

    @Test
    void databaseDefaultsAreUtf8mb4UnicodeCi() {
        String collation = jdbcTemplate.queryForObject(
                "SELECT CONCAT(default_character_set_name, '/', default_collation_name) "
                        + "FROM information_schema.schemata WHERE schema_name = DATABASE()",
                String.class);
        assertThat(collation).isEqualTo("utf8mb4/utf8mb4_unicode_ci");
    }

    @Test
    void tableWithoutCharsetClauseInheritsUnicodeCi() {
        // docs/03 DDL 규칙 검증: CHARSET/COLLATE 생략 시 DB 기본값(utf8mb4_unicode_ci)을 따른다
        jdbcTemplate.execute("CREATE TEMPORARY TABLE ddl_rule_check (x VARCHAR(10))");
        try {
            String ddl = jdbcTemplate.queryForObject("SHOW CREATE TABLE ddl_rule_check",
                    (rs, rowNum) -> rs.getString(2));
            assertThat(ddl).contains("COLLATE=utf8mb4_unicode_ci");
        } finally {
            jdbcTemplate.execute("DROP TEMPORARY TABLE ddl_rule_check");
        }
    }

    @Test
    void sessionTimeZoneIsUtc() {
        assertThat(jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
    }

    @Test
    void flywaySafetySettingsAreApplied() {
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void healthIsUpWithDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }
}
