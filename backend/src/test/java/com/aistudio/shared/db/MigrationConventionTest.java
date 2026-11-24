package com.aistudio.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Flyway migration 규칙 검사 (ADR-009, ADR-014, docs/03_DATABASE.md).
 */
class MigrationConventionTest {

    private static final Pattern FILE_NAME = Pattern.compile("V\\d+__[a-z0-9_]+\\.sql");

    /** 적용된 migration은 수정 금지. 새 migration은 여기에 추가하고, 기존 값은 바꾸지 않는다. */
    private static final Map<String, String> APPLIED_SHA256 = Map.of(
            "V1__initial_baseline.sql", "ad01a05c036f7d63f690a1197e83706a67c83b0c0dd6652744cb481cbf8dbc79",
            "V2__fix_database_collation.sql", "7e65c9546f7333d41c6611d5e9eccca75e7d936cc0572ac2d4796c687e977151");

    /** V1은 ADR-014 이전 기준선이므로 collation 규칙 검사에서 제외한다 (수정 금지). */
    private static final List<String> COLLATION_RULE_EXEMPT = List.of("V1__initial_baseline.sql");

    private static Map<String, String> migrations() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql");
        Map<String, String> contents = new TreeMap<>();
        for (Resource resource : resources) {
            String text = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            contents.put(resource.getFilename(), text);
        }
        return contents;
    }

    @Test
    void fileNamesFollowConvention() throws IOException {
        assertThat(migrations().keySet()).allMatch(name -> FILE_NAME.matcher(name).matches());
    }

    @Test
    void appliedMigrationsAreUnchanged() throws IOException {
        Map<String, String> contents = migrations();
        APPLIED_SHA256.forEach((name, expected) -> {
            assertThat(contents).as("적용된 migration 삭제 금지: %s", name).containsKey(name);
            assertThat(sha256(contents.get(name))).as("적용된 migration 수정 금지: %s", name).isEqualTo(expected);
        });
    }

    @Test
    void newMigrationsAreRegisteredForChangeDetection() throws IOException {
        assertThat(APPLIED_SHA256.keySet())
                .as("새 migration은 적용 후 APPLIED_SHA256에 등록한다")
                .containsAll(migrations().keySet());
    }

    @Test
    void charsetIsAlwaysPairedWithUnicodeCiCollation() throws IOException {
        // MariaDB 11.2+: CHARSET만 지정하면 DB 기본값이 아닌 character_set_collations(uca1400)가 적용된다
        migrations().forEach((name, text) -> {
            if (COLLATION_RULE_EXEMPT.contains(name)) {
                return;
            }
            String sql = text.replaceAll("--[^\n]*", "").toLowerCase(Locale.ROOT);
            Arrays.stream(sql.split(";"))
                    .filter(statement -> statement.contains("charset") || statement.contains("character set"))
                    .forEach(statement -> assertThat(statement)
                            .as("%s: CHARSET 지정 시 COLLATE utf8mb4_unicode_ci 명시", name)
                            .containsPattern("collate\\s*=?\\s*utf8mb4_unicode_ci"));
            assertThat(sql).as("%s: uca1400 collation 사용 금지", name).doesNotContain("uca1400");
        });
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
