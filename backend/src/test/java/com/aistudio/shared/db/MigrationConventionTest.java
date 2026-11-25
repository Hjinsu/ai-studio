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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
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
            "V2__fix_database_collation.sql", "7e65c9546f7333d41c6611d5e9eccca75e7d936cc0572ac2d4796c687e977151",
            "V3__create_admin_and_audit.sql", "4224b08f5e0149829b27e1dcef51fd7b7e3f33faa6720a975834eed23b1b6384",
            "V4__create_channel.sql", "06d9de8f625d4575f9bc278263cd70a336535305a018bc3e977dcfd2e47d98ec",
            "V5__create_budget_and_ledger.sql", "a5d798c38c636d56d1974d8eb4494db46e9fcd8916ebd65d8ac3de325aa9ea5d");

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

    /**
     * 정확 비교가 필요한 컬럼만 utf8mb4_bin을 허용한다(ADR-053). 그 외 COLLATE 지정은 utf8mb4_unicode_ci만 허용.
     * 목록을 바꾸려면 설계(docs/design/T02_DATA_MODEL.md §2)와 함께 바꾼다.
     */
    private static final Set<String> BINARY_COLLATION_COLUMNS = Set.of(
            "admin_user.password_hash",
            "budget_reservation.request_key",
            "budget_reservation.request_fingerprint",
            "cost_log.event_key",
            "cost_log.event_fingerprint",
            "cost_log.request_key");

    private static final Pattern CREATE_TABLE = Pattern.compile("create\\s+table\\s+(\\w+)");
    private static final Pattern COLUMN_COLLATE = Pattern.compile("^\\s*(\\w+)\\s+[^\\n]*?\\bcollate\\s*=?\\s*(\\w+)");

    @Test
    void columnCollationsAreUnicodeCiOrAllowedBinary() throws IOException {
        Set<String> binaryColumnsFound = new TreeSet<>();
        migrations().forEach((name, text) -> {
            if (COLLATION_RULE_EXEMPT.contains(name)) {
                return;
            }
            String sql = text.replaceAll("--[^\n]*", "").toLowerCase(Locale.ROOT);
            for (String statement : sql.split(";")) {
                Matcher table = CREATE_TABLE.matcher(statement);
                String tableName = table.find() ? table.group(1) : "(none)";
                for (String line : statement.split("\n")) {
                    Matcher column = COLUMN_COLLATE.matcher(line);
                    if (!column.find() || line.trim().startsWith("alter database")) {
                        continue;
                    }
                    String collation = column.group(2);
                    String qualified = tableName + "." + column.group(1);
                    if (collation.equals("utf8mb4_bin")) {
                        assertThat(BINARY_COLLATION_COLUMNS)
                                .as("%s: utf8mb4_bin 허용 목록에 없는 컬럼 %s", name, qualified)
                                .contains(qualified);
                        binaryColumnsFound.add(qualified);
                    } else {
                        assertThat(collation).as("%s: %s collation", name, qualified).isEqualTo("utf8mb4_unicode_ci");
                    }
                }
            }
        });
        assertThat(binaryColumnsFound).as("허용 목록과 실제 utf8mb4_bin 컬럼 일치").isEqualTo(BINARY_COLLATION_COLUMNS);
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
