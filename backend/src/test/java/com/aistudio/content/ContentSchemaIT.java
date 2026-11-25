package com.aistudio.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.aistudio.TestDatabases;

/**
 * V6 DB 계약 직접 검증(DB-02~04, FV-02/04): 복합 소유권 FK, NULL 우회 거절, 불변·상태 트리거, CHECK.
 * 앱 로직을 거치지 않고 SQL로 위반을 시도해 DB가 최종 방어하는지 확인한다. 격리 테스트 DB 전용.
 */
@Tag("integration")
class ContentSchemaIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static JdbcTemplate jdbc;
    static ContentFixtures fx;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(DB.dataSource()).locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate();
        jdbc = new JdbcTemplate(DB.dataSource());
        fx = new ContentFixtures(jdbc);
    }

    private static void rejected(String sql, String message, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).hasStackTraceContaining(message);
    }

    private static final String FK = "foreign key constraint fails";

    // ---------------------------------------------------------------- 신규 키·FK 구조
    @Test
    void ownershipKeysAndForeignKeysExist() {
        List<String> uniques = jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', constraint_name) FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE() AND constraint_type = 'UNIQUE' ORDER BY 1""", String.class);
        assertThat(uniques).contains("channel_config.uk_channel_config_owner", "channel_config.uk_channel_config_revision",
                "topic.uk_topic_normalized", "topic.uk_topic_owner", "content.uk_content_channel",
                "content.uk_content_type", "content.uk_content_channel_type", "content_revision.uk_content_revision_no",
                "content_revision.uk_content_revision_owner", "content_run.uk_content_run_no",
                "content_run.uk_content_run_owner", "queue_command_log.uk_queue_command_key");
        List<String> ownerColumns = jdbc.queryForList("""
                SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position) FROM information_schema.key_column_usage
                 WHERE table_schema = DATABASE() AND table_name = 'content' AND constraint_name = 'uk_content_channel_type'
                """, String.class);
        assertThat(ownerColumns).containsExactly("id,channel_id,content_type");
        List<String> fks = jdbc.queryForList("""
                SELECT CONCAT(k.table_name, '.', k.constraint_name, '(',
                       GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position), ')->', MAX(k.referenced_table_name), '(',
                       GROUP_CONCAT(k.referenced_column_name ORDER BY k.ordinal_position), ')')
                  FROM information_schema.key_column_usage k
                 WHERE k.table_schema = DATABASE() AND k.referenced_table_name IS NOT NULL
                   AND k.table_name IN ('topic', 'content', 'content_revision', 'content_run', 'queue_command_log')
                 GROUP BY k.table_name, k.constraint_name ORDER BY 1""", String.class);
        assertThat(fks).contains(
                "content.fk_content_topic(topic_id,channel_id)->topic(id,channel_id)",
                "content.fk_content_current_revision(id,current_revision_id)->content_revision(content_id,id)",
                "content.fk_content_active_run(id,active_run_id)->content_run(content_id,id)",
                "content_revision.fk_revision_content(content_id,channel_id)->content(id,channel_id)",
                "content_revision.fk_revision_channel_config(channel_id,channel_config_id)->channel_config(channel_id,id)",
                "content_revision.fk_revision_base_run(content_id,base_run_id)->content_run(content_id,id)",
                "content_run.fk_run_revision(content_id,content_revision_id)->content_revision(content_id,id)");
        // FK는 RESTRICT(삭제·cascade 없음)
        Integer cascades = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()
                   AND (delete_rule <> 'RESTRICT' OR update_rule <> 'RESTRICT')""", Integer.class);
        assertThat(cascades).isZero();
    }

    // ---------------------------------------------------------------- 교차 소유권(DB-03)
    @Test
    void crossChannelOwnershipIsRejected() {
        ContentFixtures.Graph a = fx.graph();
        ContentFixtures.Graph b = fx.graph();
        long admin = fx.admin();
        // 다른 채널의 topic으로 콘텐츠 생성
        rejected("""
                INSERT INTO content (channel_id, topic_id, content_type, title, status, created_by, created_at, updated_at)
                VALUES (?, ?, 'SHORTS', 'x', 'DRAFT', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", FK,
                a.channelId(), b.topicId(), admin);
        // 다른 채널 설정으로 revision 생성
        rejected("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, ?, 2, ?, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""", FK,
                a.contentId(), a.channelId(), b.configId(), ContentFixtures.hash("x"), admin);
        // revision.channel_id 복제를 다른 채널로 위조(설정은 그 채널 소유) → content(id, channel_id) 불일치
        rejected("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, ?, 2, ?, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""", FK,
                a.contentId(), b.channelId(), b.configId(), ContentFixtures.hash("y"), admin);
    }

    @Test
    void crossContentPointersAreRejected() {
        ContentFixtures.Graph a = fx.graph();
        ContentFixtures.Graph b = fx.graph();
        // 다른 콘텐츠의 revision을 현재 revision으로: BEFORE UPDATE 트리거(같은 content 소유 확인)가 FK보다 먼저 거절
        rejected("UPDATE content SET current_revision_id = ?, title = 'Title 1' WHERE id = ?",
                "content title must match the current revision", b.revisionId(), a.contentId());
        // 다른 콘텐츠의 revision으로 run
        rejected("""
                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode, status,
                       requested_by, created_at)
                VALUES (?, ?, 1, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))""", FK, a.contentId(),
                b.revisionId(), fx.admin());
        // 다른 콘텐츠의 run을 활성 run으로
        long bRun = fx.run(b.contentId(), b.revisionId(), 1);
        rejected("UPDATE content SET status = 'PRODUCING', active_run_id = ? WHERE id = ?",
                "active run must be a non-terminal run of the content", bRun, a.contentId());
        // 다른 콘텐츠의 성공 run을 기준 run으로(BEFORE INSERT 트리거가 FK보다 먼저 소유·상태를 확인)
        fx.finish(b.contentId(), bRun, "SUCCEEDED");
        rejected("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, base_run_id,
                       working_title, brief, language, target_duration_seconds, revision_hash, hash_version, created_by,
                       created_at)
                VALUES (?, ?, 2, ?, ?, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""",
                "base run must be a succeeded run of the same content", a.contentId(),
                a.channelId(), a.configId(), bRun, ContentFixtures.hash("z"), fx.admin());
    }

    // ---------------------------------------------------------------- NULL 우회
    @Test
    void nullCannotBypassOwnership() {
        ContentFixtures.Graph g = fx.graph();
        long admin = fx.admin();
        rejected("UPDATE content SET current_revision_id = NULL WHERE id = ?", "current revision cannot be cleared",
                g.contentId());
        rejected("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, NULL, 2, ?, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""", "cannot be null",
                g.contentId(), g.configId(), ContentFixtures.hash("n1"), admin);
        rejected("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, ?, 2, NULL, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""", "cannot be null",
                g.contentId(), g.channelId(), ContentFixtures.hash("n2"), admin);
        rejected("""
                INSERT INTO content (channel_id, topic_id, content_type, title, status, created_by, created_at, updated_at)
                VALUES (?, NULL, 'SHORTS', 'x', 'DRAFT', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "cannot be null",
                g.channelId(), admin);
        rejected("""
                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode, status,
                       requested_by, created_at)
                VALUES (?, NULL, 1, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))""", "cannot be null",
                g.contentId(), admin);
        // PRODUCING인데 활성 포인터 NULL(CHECK)
        rejected("UPDATE content SET status = 'PRODUCING' WHERE id = ?", "ck_content_active_run", g.contentId());
        // 포인터를 가진 채로 생성(같은 TX 설정 규칙 우회) 금지
        rejected("""
                INSERT INTO content (channel_id, topic_id, content_type, title, status, current_revision_id, created_by,
                       created_at, updated_at)
                VALUES (?, ?, 'SHORTS', 'x', 'DRAFT', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                "content must be created as DRAFT without pointers", g.channelId(), g.topicId(), g.revisionId(), admin);
    }

    // ---------------------------------------------------------------- 불변 트리거(DB-02)
    @Test
    void immutableRowsRejectUpdateAndDelete() {
        ContentFixtures.Graph g = fx.graph();
        rejected("UPDATE topic SET note = 'x' WHERE id = ?", "topic is immutable", g.topicId());
        rejected("DELETE FROM topic WHERE id = ?", "topic is immutable", g.topicId());
        rejected("UPDATE content_revision SET brief = 'changed' WHERE id = ?", "content_revision is immutable",
                g.revisionId());
        rejected("DELETE FROM content_revision WHERE id = ?", "content_revision is immutable", g.revisionId());
        rejected("DELETE FROM content WHERE id = ?", "content cannot be deleted", g.contentId());
        jdbc.update("""
                INSERT INTO queue_command_log (request_key, request_fingerprint, command_type, target_type, target_id,
                       actor_user_id, created_at)
                VALUES ('schema-key-1', ?, 'CREATE_TOPIC', 'TOPIC', ?, ?, UTC_TIMESTAMP(6))""",
                ContentFixtures.hash("fp"), g.topicId(), fx.admin());
        rejected("UPDATE queue_command_log SET target_id = 1 WHERE request_key = 'schema-key-1'",
                "queue_command_log is append-only");
        rejected("DELETE FROM queue_command_log WHERE request_key = 'schema-key-1'", "queue_command_log is append-only");
        rejected("UPDATE channel_config SET change_note = 'x' WHERE id = ?", "immutable", g.configId());
    }

    @Test
    void contentOwnerColumnsAreImmutableAndArchiveIsTerminal() {
        ContentFixtures.Graph g = fx.graph();
        ContentFixtures.Graph other = fx.graph();
        rejected("UPDATE content SET channel_id = ? WHERE id = ?", "content owner columns are immutable",
                other.channelId(), g.contentId());
        rejected("UPDATE content SET content_type = 'LONGFORM' WHERE id = ?", "content owner columns are immutable",
                g.contentId());
        long sameChannelTopic = fx.topic(g.channelId());
        rejected("UPDATE content SET topic_id = ? WHERE id = ?", "content owner columns are immutable", sameChannelTopic,
                g.contentId());
        rejected("UPDATE content SET created_by = created_by + 1 WHERE id = ?", "content owner columns are immutable",
                g.contentId());
        jdbc.update("UPDATE content SET status = 'ARCHIVED', archived_at = UTC_TIMESTAMP(6) WHERE id = ?", g.contentId());
        rejected("UPDATE content SET version = version + 1 WHERE id = ?", "archived content is immutable",
                g.contentId());
        rejected("UPDATE content SET status = 'DRAFT', archived_at = NULL WHERE id = ?", "archived content is immutable",
                g.contentId());
        // CHECK: ARCHIVED ↔ archived_at
        rejected("UPDATE content SET status = 'ARCHIVED' WHERE id = ?", "ck_content_archived", other.contentId());
    }

    @Test
    void titleMustMatchTheCurrentRevision() {
        ContentFixtures.Graph g = fx.graph();
        rejected("UPDATE content SET title = 'Different' WHERE id = ?", "content title must match the current revision",
                g.contentId());
        long rev2 = fx.revision(g.contentId(), g.channelId(), g.configId(), 2, "Title 2");
        // 포인터만 바꾸고 제목 복제본을 갱신하지 않으면 거절
        rejected("UPDATE content SET current_revision_id = ? WHERE id = ?",
                "content title must match the current revision", rev2, g.contentId());
        // 대소문자만 다른 제목도 거절(정확 비교)
        rejected("UPDATE content SET current_revision_id = ?, title = 'title 2' WHERE id = ?",
                "content title must match the current revision", rev2, g.contentId());
        jdbc.update("UPDATE content SET current_revision_id = ?, title = 'Title 2', version = version + 1 WHERE id = ?",
                rev2, g.contentId());
        assertThat(jdbc.queryForObject("SELECT title FROM content WHERE id = ?", String.class, g.contentId()))
                .isEqualTo("Title 2");
        // 같은 revision 번호 중복
        rejected("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, ?, 2, ?, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""", "uk_content_revision_no",
                g.contentId(), g.channelId(), g.configId(), ContentFixtures.hash("dup"), fx.admin());
    }

    // ---------------------------------------------------------------- 활성 run 포인터(FV-04)
    @Test
    void activeRunPointerOnlyReferencesNonTerminalRunsOfTheContent() {
        ContentFixtures.Graph g = fx.graph();
        long run1 = fx.activate(g.contentId(), g.revisionId(), 1);
        // 활성 중 revision 포인터 변경 금지
        long rev2 = fx.revision(g.contentId(), g.channelId(), g.configId(), 2, "Title 2");
        rejected("UPDATE content SET current_revision_id = ?, title = 'Title 2' WHERE id = ?",
                "current revision cannot change while a run is active", rev2, g.contentId());
        // 포인터를 해제하지 않고 run 종료 금지(R10 순서)
        jdbc.update("UPDATE content_run SET status = 'RUNNING', started_at = UTC_TIMESTAMP(6) WHERE id = ?", run1);
        rejected("UPDATE content_run SET status = 'SUCCEEDED', finished_at = UTC_TIMESTAMP(6) WHERE id = ?",
                "release the active run pointer before finishing the run", run1);
        fx.finish(g.contentId(), run1, "FAILED");
        // 종료된 run을 다시 활성으로 지정 금지
        rejected("UPDATE content SET status = 'PRODUCING', active_run_id = ? WHERE id = ?",
                "active run must be a non-terminal run of the content", run1, g.contentId());
        // 같은 run 번호 중복
        rejected("""
                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode, status,
                       requested_by, created_at)
                VALUES (?, ?, 1, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))""", "uk_content_run_no",
                g.contentId(), g.revisionId(), fx.admin());
        // 새 run으로는 다시 활성 가능
        assertThatCode(() -> fx.activate(g.contentId(), g.revisionId(), 2)).doesNotThrowAnyException();
    }

    @Test
    void runLifecycleConstraintsAndTerminalImmutability() {
        ContentFixtures.Graph g = fx.graph();
        long admin = fx.admin();
        String insertRun = """
                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode, status,
                       output_manifest_hash, requested_by, created_at)
                VALUES (?, ?, ?, 'fake-v1', ?, ?, ?, ?, UTC_TIMESTAMP(6))""";
        // FAKE 전용(FD-13), 생성 시 QUEUED만
        rejected(insertRun, "ck_run_mode", g.contentId(), g.revisionId(), 1, "LIVE", "QUEUED", null, admin);
        rejected(insertRun, "content_run must be created as QUEUED", g.contentId(), g.revisionId(), 1, "FAKE",
                "RUNNING", null, admin);
        long run = fx.run(g.contentId(), g.revisionId(), 1);
        rejected("UPDATE content_run SET status = 'RUNNING' WHERE id = ?", "ck_run_started", run);
        rejected("UPDATE content_run SET status = 'FAILED', started_at = UTC_TIMESTAMP(6) WHERE id = ?",
                "ck_run_finished", run);
        rejected("UPDATE content_run SET execution_mode = 'LIVE' WHERE id = ?", "content_run identity columns are immutable",
                run);
        rejected("UPDATE content_run SET pipeline_version = 'fake-v2' WHERE id = ?",
                "content_run identity columns are immutable", run);
        rejected("UPDATE content_run SET run_no = 9 WHERE id = ?", "content_run identity columns are immutable", run);
        rejected("UPDATE content_run SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", "ck_run_cancel", run);
        jdbc.update("""
                UPDATE content_run SET cancel_requested_at = UTC_TIMESTAMP(6), cancel_requested_by = ?,
                       cancel_reason = 'stop' WHERE id = ?""", admin, run);
        rejected("UPDATE content_run SET cancel_reason = 'other' WHERE id = ?", "content_run cancel intent is immutable",
                run);
        jdbc.update("UPDATE content_run SET status = 'RUNNING', started_at = UTC_TIMESTAMP(6) WHERE id = ?", run);
        rejected("UPDATE content_run SET status = 'QUEUED', started_at = NULL WHERE id = ?",
                "content_run cannot return to QUEUED", run);
        // FAKE 성공 run은 manifest를 가질 수 없다
        rejected("UPDATE content_run SET status = 'SUCCEEDED', finished_at = UTC_TIMESTAMP(6), output_manifest_hash = ?"
                + " WHERE id = ?", "ck_run_fake_manifest", ContentFixtures.hash("m"), run);
        jdbc.update("UPDATE content_run SET status = 'CANCELLED', finished_at = UTC_TIMESTAMP(6) WHERE id = ?", run);
        rejected("UPDATE content_run SET version = version + 1 WHERE id = ?", "terminal content_run is immutable", run);
        rejected("DELETE FROM content_run WHERE id = ?", "content_run cannot be deleted", run);
    }

    @Test
    void baseRunMustBeASucceededRunOfTheSameContent() {
        ContentFixtures.Graph g = fx.graph();
        long failed = fx.run(g.contentId(), g.revisionId(), 1);
        fx.finish(g.contentId(), failed, "FAILED");
        String insert = """
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, base_run_id,
                       working_title, brief, language, target_duration_seconds, revision_hash, hash_version, created_by,
                       created_at)
                VALUES (?, ?, 2, ?, ?, 'x', 'b', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))""";
        rejected(insert, "base run must be a succeeded run of the same content", g.contentId(), g.channelId(),
                g.configId(), failed, ContentFixtures.hash("b1"), fx.admin());
        long succeeded = fx.run(g.contentId(), g.revisionId(), 2);
        fx.finish(g.contentId(), succeeded, "SUCCEEDED");
        assertThatCode(() -> jdbc.update(insert, g.contentId(), g.channelId(), g.configId(), succeeded,
                ContentFixtures.hash("b2"), fx.admin())).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- CHECK·UNIQUE
    @Test
    void topicUniquenessIsPerChannel() {
        long channelA = fx.channel();
        long channelB = fx.channel();
        String hash = ContentFixtures.hash("same-title");
        fx.topic(channelA, hash);
        assertThatThrownBy(() -> fx.topic(channelA, hash)).hasStackTraceContaining("uk_topic_normalized");
        assertThatCode(() -> fx.topic(channelB, hash)).doesNotThrowAnyException();
        assertThatThrownBy(() -> fx.topic(channelA, "NOT-A-HASH")).hasStackTraceContaining("ck_topic_hash");
        assertThatThrownBy(() -> fx.topic(channelA, hash.toUpperCase())).hasStackTraceContaining("ck_topic_hash");
    }

    @Test
    void revisionAndCommandLogChecks() {
        ContentFixtures.Graph g = fx.graph();
        String insert = """
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, ?, 2, ?, 'x', 'b', ?, ?, ?, 1, ?, UTC_TIMESTAMP(6))""";
        rejected(insert, "ck_revision_language", g.contentId(), g.channelId(), g.configId(), "EN", 30,
                ContentFixtures.hash("l"), fx.admin());
        rejected(insert, "ck_revision_duration", g.contentId(), g.channelId(), g.configId(), "en", 0,
                ContentFixtures.hash("d0"), fx.admin());
        rejected(insert, "ck_revision_duration", g.contentId(), g.channelId(), g.configId(), "en", 14401,
                ContentFixtures.hash("d1"), fx.admin());
        String log = """
                INSERT INTO queue_command_log (request_key, request_fingerprint, command_type, target_type, target_id,
                       actor_user_id, created_at)
                VALUES (?, ?, ?, 'TOPIC', 1, ?, UTC_TIMESTAMP(6))""";
        rejected(log, "ck_queue_command_key", "short", ContentFixtures.hash("k"), "CREATE_TOPIC", fx.admin());
        rejected(log, "ck_queue_command_type", "valid-key-1", ContentFixtures.hash("k"), "DROP_ALL", fx.admin());
        jdbc.update(log, "valid-key-2", ContentFixtures.hash("k"), "CREATE_TOPIC", fx.admin());
        // 키 공간은 전역이며 대소문자를 구분한다(utf8mb4_bin)
        rejected(log, "uk_queue_command_key", "valid-key-2", ContentFixtures.hash("k2"), "CREATE_CONTENT", fx.admin());
        assertThatCode(() -> jdbc.update(log, "VALID-KEY-2", ContentFixtures.hash("k"), "CREATE_TOPIC", fx.admin()))
                .doesNotThrowAnyException();
    }
}
