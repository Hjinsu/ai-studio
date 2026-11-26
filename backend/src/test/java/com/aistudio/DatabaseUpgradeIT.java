package com.aistudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aistudio.shared.db.DatabaseGuardTestAccess;

/**
 * 기존 DB 업그레이드: 로컬 ai_studio와 같은 V1(collation uca1400) → V2 상태를 만든 뒤 최신(V5)으로 migrate한다 (ADR-014, T02).
 * 허용되지 않은 DB에서는 Flyway 전에 중단하는지도 검증한다 (ADR-024). Docker 필요.
 */
@Tag("integration")
class DatabaseUpgradeIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(DB.dataSource())
                .locations("classpath:db/migration")
                .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String databaseCollation(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT default_collation_name FROM information_schema.schemata "
                + "WHERE schema_name = DATABASE()", String.class);
    }

    @Test
    void refusesToMigrateUnexpectedDatabaseThenUpgradesV1DatabaseToLatest() {
        JdbcTemplate jdbc = new JdbcTemplate(DB.dataSource());

        // 1) 허용 DB 이름이 다르면 Flyway 전에 중단하고 스키마 이력을 만들지 않는다
        assertThatThrownBy(() -> DatabaseGuardTestAccess.guardedMigrate(flyway(null), "ai_studio_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DB.name());
        Integer historyTables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'", Integer.class);
        assertThat(historyTables).isZero();

        // 2) 로컬 ai_studio와 같은 V1 상태 재현: collation이 서버 매핑값(uca1400)으로 바뀐다
        flyway("1").migrate();
        assertThat(databaseCollation(jdbc)).isEqualTo("utf8mb4_uca1400_ai_ci");

        // 3) V2 기존 DB 상태 재현 후 최신(V5)으로 업그레이드
        flyway("2").migrate();
        assertThat(databaseCollation(jdbc)).isEqualTo("utf8mb4_unicode_ci");
        DatabaseGuardTestAccess.guardedMigrate(flyway(null), DB.name());
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "9", "10", "11", "12");
        assertThat(databaseCollation(jdbc)).isEqualTo("utf8mb4_unicode_ci");
        assertThat(flyway(null).info().pending()).isEmpty();
        // V6(T03-B): 기존 channel_config에 소유권 대체 키 추가, 도메인 테이블 생성. T02 데이터 보존은 ContentUpgradeIT
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints"
                + " WHERE table_schema = DATABASE() AND table_name = 'channel_config'"
                + " AND constraint_name = 'uk_channel_config_owner' AND constraint_type = 'UNIQUE'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class)).contains("topic", "content", "content_revision", "content_run", "queue_command_log");
    }
}
