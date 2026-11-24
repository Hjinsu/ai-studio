package com.aistudio.shared.db;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 다른 패키지의 통합 테스트에서 운영과 같은 Flyway 보호 로직을 쓰기 위한 테스트 전용 접근자.
 */
public final class DatabaseGuardTestAccess {

    private DatabaseGuardTestAccess() {
    }

    public static void guardedMigrate(Flyway flyway, String expectedDatabase) {
        DatabaseGuardConfiguration.guardedMigrate(flyway, null, expectedDatabase,
                dataSource -> new JdbcTemplate(dataSource).queryForObject("SELECT DATABASE()", String.class));
    }
}
