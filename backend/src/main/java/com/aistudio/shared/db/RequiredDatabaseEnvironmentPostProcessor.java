package com.aistudio.shared.db;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * DB 연결 전에 필수 환경변수와 접속 대상 DB 이름을 검증한다 (ADR-013, ADR-024).
 * AI_STUDIO_ 접두사 변수만 사용하며 다른 프로젝트의 DB_* 변수는 대체값으로 쓰지 않는다.
 * .env import와 profile 결정 이후에 실행되도록 가장 낮은 우선순위로 둔다.
 * test profile은 Testcontainers가 접속 정보를 주므로 환경변수 검사를 하지 않는다(접속 DB 검증은 DatabaseGuardConfiguration).
 */
public class RequiredDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final List<String> REQUIRED_VARIABLES = List.of(
            "AI_STUDIO_DB_URL", "AI_STUDIO_DB_USER", "AI_STUDIO_DB_PASSWORD");

    /** 운영은 migration 계정과 앱 계정을 분리한다 (ADR-025) */
    static final List<String> PROD_MIGRATION_VARIABLES = List.of(
            "AI_STUDIO_DB_MIGRATION_USER", "AI_STUDIO_DB_MIGRATION_PASSWORD");

    private static final Profiles DATABASE_PROFILES = Profiles.of("local | docker | prod");
    private static final Profiles PROD = Profiles.of("prod");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(DATABASE_PROFILES)) {
            return;
        }
        List<String> required = new ArrayList<>(REQUIRED_VARIABLES);
        if (environment.acceptsProfiles(PROD)) {
            required.addAll(PROD_MIGRATION_VARIABLES);
        }
        List<String> missing = required.stream()
                .filter(name -> isBlank(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("필수 환경변수가 설정되지 않았습니다: " + String.join(", ", missing)
                    + ". 저장소 루트 .env 또는 OS 환경변수에 설정하세요(.env.example 참고).");
        }
        String expectedDatabase = environment.getProperty(DatabaseTarget.EXPECTED_DATABASE_PROPERTY);
        if (isBlank(expectedDatabase)) {
            throw new IllegalStateException(DatabaseTarget.EXPECTED_DATABASE_PROPERTY
                    + " 설정이 없습니다. 허용 DB를 지정하지 않은 profile로는 실행할 수 없습니다.");
        }
        DatabaseTarget.verifyUrl(environment.getProperty("AI_STUDIO_DB_URL"), expectedDatabase);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
