package com.aistudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import com.aistudio.shared.db.DatabaseGuardTestAccess;

/**
 * 기존 V1 DB 업그레이드: 로컬 ai_studio와 같은 상태(V1만 적용, collation uca1400)를 만든 뒤 최신으로 migrate한다 (ADR-014).
 * 허용되지 않은 DB에서는 Flyway 전에 중단하는지도 검증한다 (ADR-024). Docker 필요.
 */
@Tag("integration")
@Testcontainers
class DatabaseUpgradeIT {

    @Container
    static final MariaDBContainer MARIADB = TestDatabases.isolatedDatabase("ai_studio_upgrade_test");

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword());
        dataSource.setDriverClassName("org.mariadb.jdbc.Driver");
        return dataSource;
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource())
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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        // 1) 허용 DB 이름이 다르면 Flyway 전에 중단하고 스키마 이력을 만들지 않는다
        assertThatThrownBy(() -> DatabaseGuardTestAccess.guardedMigrate(flyway(null), "ai_studio_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ai_studio_upgrade_test");
        Integer historyTables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'", Integer.class);
        assertThat(historyTables).isZero();

        // 2) 로컬 ai_studio와 같은 V1 상태 재현: collation이 서버 매핑값(uca1400)으로 바뀐다
        flyway("1").migrate();
        assertThat(databaseCollation(jdbc)).isEqualTo("utf8mb4_uca1400_ai_ci");

        // 3) 최신으로 업그레이드: V2가 utf8mb4_unicode_ci로 되돌린다
        DatabaseGuardTestAccess.guardedMigrate(flyway(null), "ai_studio_upgrade_test");
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2");
        assertThat(databaseCollation(jdbc)).isEqualTo("utf8mb4_unicode_ci");
        assertThat(flyway(null).info().pending()).isEmpty();
    }
}
