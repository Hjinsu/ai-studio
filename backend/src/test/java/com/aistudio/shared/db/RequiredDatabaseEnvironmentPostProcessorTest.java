package com.aistudio.shared.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class RequiredDatabaseEnvironmentPostProcessorTest {

    private static final String PASSWORD = "dummy-test-password";

    private final RequiredDatabaseEnvironmentPostProcessor processor = new RequiredDatabaseEnvironmentPostProcessor();

    private static MockEnvironment localEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty(DatabaseTarget.EXPECTED_DATABASE_PROPERTY, "ai_studio");
        return environment;
    }

    private static MockEnvironment withAiStudioVariables(MockEnvironment environment, String url) {
        return environment
                .withProperty("AI_STUDIO_DB_URL", url)
                .withProperty("AI_STUDIO_DB_USER", "ai_studio")
                .withProperty("AI_STUDIO_DB_PASSWORD", PASSWORD);
    }

    @Test
    void acceptsCompleteLocalConfiguration() {
        MockEnvironment environment = withAiStudioVariables(localEnvironment(), "jdbc:mariadb://localhost:3306/ai_studio");

        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void failsWithMissingVariableNamesOnly() {
        MockEnvironment environment = localEnvironment()
                .withProperty("AI_STUDIO_DB_URL", "jdbc:mariadb://localhost:3306/ai_studio");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_STUDIO_DB_USER")
                .hasMessageContaining("AI_STUDIO_DB_PASSWORD")
                .hasMessageNotContaining("jdbc:");
    }

    @Test
    void doesNotFallBackToOtherProjectDbVariables() {
        // 다른 프로젝트(stock_analysis)의 OS 환경변수가 있어도 AI_STUDIO_ 변수가 없으면 중단한다 (ADR-013)
        MockEnvironment environment = localEnvironment()
                .withProperty("DB_URL", "jdbc:mariadb://localhost:3306/stock_analysis")
                .withProperty("DB_USERNAME", "stock_app")
                .withProperty("DB_PASSWORD", PASSWORD);

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_STUDIO_DB_URL")
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void rejectsNonAiStudioDatabaseBeforeConnecting() {
        MockEnvironment environment = withAiStudioVariables(localEnvironment(),
                "jdbc:mariadb://localhost:3306/stock_analysis");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_analysis")
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void requiresVariablesForProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_STUDIO_DB_PASSWORD");
    }

    @Test
    void prodRequiresSeparateMigrationAccount() {
        MockEnvironment environment = withAiStudioVariables(new MockEnvironment(),
                "jdbc:mariadb://db.internal:3306/ai_studio");
        environment.setActiveProfiles("prod");
        environment.setProperty(DatabaseTarget.EXPECTED_DATABASE_PROPERTY, "ai_studio");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_STUDIO_DB_MIGRATION_USER")
                .hasMessageContaining("AI_STUDIO_DB_MIGRATION_PASSWORD");

        environment.setProperty("AI_STUDIO_DB_MIGRATION_USER", "ai_studio_migrator");
        environment.setProperty("AI_STUDIO_DB_MIGRATION_PASSWORD", PASSWORD);
        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }

    // ADR-024: docker/prod도 허용 DB 검증을 적용한다
    @Test
    void dockerRejectsNonAiStudioDatabase() {
        MockEnvironment environment = withAiStudioVariables(new MockEnvironment(),
                "jdbc:mariadb://mariadb:3306/stock_analysis");
        environment.setActiveProfiles("docker");
        environment.setProperty(DatabaseTarget.EXPECTED_DATABASE_PROPERTY, "ai_studio");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_analysis");
    }

    @Test
    void failsWhenExpectedDatabaseIsNotConfigured() {
        MockEnvironment environment = withAiStudioVariables(new MockEnvironment(),
                "jdbc:mariadb://mariadb:3306/ai_studio");
        environment.setActiveProfiles("docker");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DatabaseTarget.EXPECTED_DATABASE_PROPERTY);
    }

    @Test
    void skipsTestProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertThatCode(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .doesNotThrowAnyException();
    }
}
