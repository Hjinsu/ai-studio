package com.aistudio.shared.db;

/**
 * 접속 대상 DB 검증 규칙 (ADR-010, ADR-013). 오류 메시지에는 URL 전체나 비밀번호를 넣지 않고 DB 이름만 쓴다.
 */
public final class DatabaseTarget {

    /** local profile에서 접속해야 하는 DB 이름을 지정하는 설정 키 */
    public static final String EXPECTED_DATABASE_PROPERTY = "ai-studio.datasource.expected-database";

    private DatabaseTarget() {
    }

    /**
     * JDBC URL에서 DB 이름을 꺼낸다. 예: jdbc:mariadb://localhost:3306/ai_studio?x=y → ai_studio. 없으면 null.
     */
    static String databaseNameFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        int authorityStart = jdbcUrl.indexOf("//");
        if (authorityStart < 0) {
            return null;
        }
        int pathStart = jdbcUrl.indexOf('/', authorityStart + 2);
        if (pathStart < 0) {
            return null;
        }
        int end = jdbcUrl.length();
        int query = jdbcUrl.indexOf('?', pathStart);
        if (query >= 0) {
            end = query;
        }
        int semicolon = jdbcUrl.indexOf(';', pathStart);
        if (semicolon >= 0 && semicolon < end) {
            end = semicolon;
        }
        String name = jdbcUrl.substring(pathStart + 1, end);
        return name.isEmpty() ? null : name;
    }

    static void verifyUrl(String jdbcUrl, String expectedDatabase) {
        String actual = databaseNameFromJdbcUrl(jdbcUrl);
        if (!expectedDatabase.equals(actual)) {
            throw new IllegalStateException("AI_STUDIO_DB_URL의 DB 이름이 '" + expectedDatabase + "'가 아닙니다(현재: "
                    + (actual == null ? "없음" : "'" + actual + "'") + "). AI Studio 전용 DB만 사용할 수 있어 실행을 중단합니다.");
        }
    }

    static void verifyConnected(String connectedDatabase, String expectedDatabase) {
        if (!expectedDatabase.equals(connectedDatabase)) {
            throw new IllegalStateException("실제 접속한 DB가 '" + expectedDatabase + "'가 아닙니다(현재: '" + connectedDatabase
                    + "'). Flyway를 실행하지 않고 중단합니다.");
        }
    }
}
