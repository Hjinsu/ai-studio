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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 신규 DB 초기화: 빈 격리 DB에 애플리케이션 기동(Flyway V1→V2) 후 DB 상태와 health를 검증한다. Docker 필요 (ADR-011).
 * Docker가 없으면 실패한다. 미실행을 통과로 숨기지 않기 위해 disabledWithoutDocker를 쓰지 않는다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseBootstrapIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry);
    }

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
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void connectedToIsolatedTestDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(DB.name()).startsWith("ai_studio_test_");
    }

    @Test
    void t02TablesExistWithUnicodeCiExceptBinaryColumns() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                String.class);
        assertThat(tables).contains("admin_user", "admin_bootstrap", "audit_event", "channel", "channel_config",
                "budget_control", "exchange_rate", "budget_policy", "budget_policy_revision", "budget_bucket",
                "budget_reservation", "budget_reservation_bucket", "cost_log");
        // T03-B(V6) 콘텐츠 도메인, T03-C(V7) 큐. 비용·Operation 테이블은 V8 이후(D) 범위라 아직 없다
        assertThat(tables).contains("topic", "content", "content_revision", "content_run", "queue_command_log")
                .contains("queue_control", "queue_worker_instance", "automation_job", "job_attempt", "job_dependency",
                        "job_event")
                .doesNotContain("job_operation", "reservation_intent", "operation_dispatch", "operation_receipt");
        List<String> binaryColumns = jdbcTemplate.queryForList(
                "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND collation_name = 'utf8mb4_bin' AND data_type <> 'longtext' "
                        + "ORDER BY 1", String.class);
        assertThat(binaryColumns).containsExactly("admin_user.password_hash", "automation_job.input_hash",
                "automation_job.job_key", "automation_job.lease_token", "budget_reservation.request_fingerprint",
                "budget_reservation.request_key", "content_revision.revision_hash", "content_run.output_manifest_hash",
                "cost_log.event_fingerprint", "cost_log.event_key", "cost_log.request_key",
                "job_attempt.lease_token", "job_attempt.worker_instance_id", "job_event.request_key",
                "queue_command_log.request_fingerprint", "queue_command_log.request_key",
                "queue_worker_instance.instance_id", "topic.normalized_hash");
        Integer otherCollations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND collation_name NOT IN ('utf8mb4_unicode_ci', 'utf8mb4_bin')", Integer.class);
        assertThat(otherCollations).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT spending_enabled FROM budget_control WHERE id = 1", Boolean.class))
                .isFalse();
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
