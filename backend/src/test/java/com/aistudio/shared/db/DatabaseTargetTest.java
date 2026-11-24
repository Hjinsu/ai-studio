package com.aistudio.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DatabaseTargetTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "jdbc:mariadb://localhost:3306/ai_studio, ai_studio",
            "jdbc:mariadb://localhost:3306/ai_studio?useSsl=false, ai_studio",
            "jdbc:mariadb://localhost/stock_analysis, stock_analysis",
            "jdbc:mariadb:replication://h1:3306;h2:3306/ai_studio, ai_studio",
            "jdbc:mariadb://localhost:3306/, NULL",
            "jdbc:mariadb://localhost:3306, NULL",
            "not-a-jdbc-url, NULL"})
    void extractsDatabaseName(String url, String expected) {
        assertThat(DatabaseTarget.databaseNameFromJdbcUrl(url)).isEqualTo(expected);
    }

    @Test
    void acceptsAiStudioUrl() {
        assertThatCode(() -> DatabaseTarget.verifyUrl("jdbc:mariadb://localhost:3306/ai_studio", "ai_studio"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsOtherDatabaseUrl() {
        assertThatThrownBy(() -> DatabaseTarget.verifyUrl("jdbc:mariadb://localhost:3306/stock_analysis", "ai_studio"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'stock_analysis'")
                .hasMessageContaining("ai_studio");
    }

    @Test
    void rejectsUrlWithoutDatabase() {
        assertThatThrownBy(() -> DatabaseTarget.verifyUrl("jdbc:mariadb://localhost:3306/", "ai_studio"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("없음");
    }

    @Test
    void rejectsOtherConnectedDatabase() {
        assertThatCode(() -> DatabaseTarget.verifyConnected("ai_studio", "ai_studio")).doesNotThrowAnyException();
        assertThatThrownBy(() -> DatabaseTarget.verifyConnected("stock_analysis", "ai_studio"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Flyway");
    }
}
