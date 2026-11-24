package com.aistudio.shared.db;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Function;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseGuardConfigurationTest {

    private Flyway flyway;
    private DataSource migrationDataSource;
    private final DataSource applicationDataSource = mock(DataSource.class);

    @BeforeEach
    void setUp() {
        flyway = mock(Flyway.class);
        migrationDataSource = mock(DataSource.class);
        Configuration configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(migrationDataSource);
    }

    private Function<DataSource, String> lookup(String migrationDatabase, String applicationDatabase) {
        Map<DataSource, String> databases = Map.of(
                migrationDataSource, migrationDatabase, applicationDataSource, applicationDatabase);
        return databases::get;
    }

    @Test
    void migratesWhenConnectedToExpectedDatabase() {
        DatabaseGuardConfiguration.guardedMigrate(flyway, migrationDataSource, "ai_studio",
                dataSource -> "ai_studio");

        verify(flyway).migrate();
    }

    @Test
    void refusesToMigrateOtherDatabase() {
        assertThatThrownBy(() -> DatabaseGuardConfiguration.guardedMigrate(flyway, migrationDataSource, "ai_studio",
                dataSource -> "stock_analysis"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_analysis");

        verify(flyway, never()).migrate();
    }

    // ADR-025: 운영은 migration 계정과 애플리케이션 계정이 다르므로 둘 다 확인한다
    @Test
    void verifiesBothAccountsWhenMigrationAccountIsSeparate() {
        @SuppressWarnings("unchecked")
        Function<DataSource, String> lookup = mock(Function.class);
        when(lookup.apply(migrationDataSource)).thenReturn("ai_studio");
        when(lookup.apply(applicationDataSource)).thenReturn("ai_studio");

        DatabaseGuardConfiguration.guardedMigrate(flyway, applicationDataSource, "ai_studio", lookup);

        verify(lookup, times(1)).apply(migrationDataSource);
        verify(lookup, times(1)).apply(applicationDataSource);
        verify(flyway).migrate();
    }

    @Test
    void refusesWhenApplicationAccountPointsToOtherDatabase() {
        assertThatThrownBy(() -> DatabaseGuardConfiguration.guardedMigrate(flyway, applicationDataSource,
                "ai_studio", lookup("ai_studio", "stock_analysis")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_analysis");

        verify(flyway, never()).migrate();
    }

    @Test
    void refusesWhenMigrationAccountPointsToOtherDatabase() {
        assertThatThrownBy(() -> DatabaseGuardConfiguration.guardedMigrate(flyway, applicationDataSource,
                "ai_studio", lookup("stock_analysis", "ai_studio")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_analysis");

        verify(flyway, never()).migrate();
    }

    @Test
    void refusesToMigrateWithoutExpectedDatabase() {
        @SuppressWarnings("unchecked")
        Function<DataSource, String> lookup = mock(Function.class);

        assertThatThrownBy(() -> DatabaseGuardConfiguration.guardedMigrate(flyway, applicationDataSource, "",
                lookup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected-database");

        verify(lookup, never()).apply(org.mockito.ArgumentMatchers.any());
        verify(flyway, never()).migrate();
    }
}
