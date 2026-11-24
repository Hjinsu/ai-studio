package com.aistudio;

import org.testcontainers.mariadb.MariaDBContainer;

/**
 * 통합 테스트용 격리 MariaDB 컨테이너 (ADR-024). 이미지 버전은 infra/docker-compose.yml, docs/16_SOURCES.md와 같게 유지한다.
 * 서버 collation을 로컬 ai_studio 생성 조건(utf8mb4_unicode_ci)과 맞춰 V1 적용 결과를 로컬과 동일하게 재현한다.
 */
final class TestDatabases {

    static final String IMAGE = "mariadb:11.8.8";

    private TestDatabases() {
    }

    static MariaDBContainer isolatedDatabase(String databaseName) {
        return new MariaDBContainer(IMAGE)
                .withDatabaseName(databaseName)
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci",
                        "--default-time-zone=+00:00");
    }
}
