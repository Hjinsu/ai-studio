package com.aistudio.shared.db;

import java.util.function.Function;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모든 profile에서 Flyway 실행 직전에 실제 접속 DB가 허용된 전용 DB인지 확인한다 (ADR-010, ADR-013, ADR-024).
 * 허용 DB 이름은 profile별 ai-studio.datasource.expected-database로 지정하며, 없으면 Flyway를 실행하지 않는다.
 * 운영처럼 Flyway가 별도 migration 계정을 쓰면(ADR-025) 애플리케이션 계정의 실제 접속 DB도 함께 확인한다.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseGuardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseGuardConfiguration.class);

    @Bean
    FlywayMigrationStrategy verifyingFlywayMigrationStrategy(
            @Value("${" + DatabaseTarget.EXPECTED_DATABASE_PROPERTY + ":}") String expectedDatabase,
            ObjectProvider<DataSource> applicationDataSource) {
        return flyway -> guardedMigrate(flyway, applicationDataSource.getIfAvailable(), expectedDatabase,
                DatabaseGuardConfiguration::connectedDatabase);
    }

    static void guardedMigrate(Flyway flyway, DataSource applicationDataSource, String expectedDatabase,
            Function<DataSource, String> connectedDatabaseLookup) {
        if (expectedDatabase == null || expectedDatabase.isBlank()) {
            throw new IllegalStateException(DatabaseTarget.EXPECTED_DATABASE_PROPERTY
                    + " 설정이 없어 Flyway를 실행하지 않고 중단합니다.");
        }
        DataSource migrationDataSource = flyway.getConfiguration().getDataSource();
        String migrationConnected = connectedDatabaseLookup.apply(migrationDataSource);
        DatabaseTarget.verifyConnected(migrationConnected, expectedDatabase);
        if (applicationDataSource != null && applicationDataSource != migrationDataSource) {
            String applicationConnected = connectedDatabaseLookup.apply(applicationDataSource);
            DatabaseTarget.verifyConnected(applicationConnected, expectedDatabase);
            log.info("Verified application account database '{}' before Flyway migration", applicationConnected);
        }
        log.info("Verified connected database '{}' before Flyway migration", migrationConnected);
        flyway.migrate();
    }

    private static String connectedDatabase(DataSource dataSource) {
        return new JdbcTemplate(dataSource).queryForObject("SELECT DATABASE()", String.class);
    }
}
