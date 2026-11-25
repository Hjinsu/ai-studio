package com.aistudio.content;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.aistudio.content.command.CanonicalJson;

/**
 * V6 스키마 직접 검증용 SQL fixture. 서비스를 거치지 않고 DB 제약·트리거만으로 거부되는지 확인할 때 쓴다.
 * 실행 가능한 Run 생성은 T03-C 범위이므로 content_run 행도 여기서 직접 만든다(테스트 전용).
 */
public final class ContentFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final JdbcTemplate jdbc;

    public ContentFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String hash(String seed) {
        return CanonicalJson.sha256Hex(seed);
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 첫 관리자(없으면 생성) ID. */
    public long admin() {
        Long existing = jdbc.query("SELECT id FROM admin_user ORDER BY id LIMIT 1", rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return existing;
        }
        return insert("""
                INSERT INTO admin_user (login, password_hash, role, enabled, password_changed_at, created_at, updated_at)
                VALUES ('fixture-admin', ?, 'ADMIN', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "$2a$10$" + "x".repeat(53));
    }

    public long channel() {
        return insert("""
                INSERT INTO channel (name, language, timezone, status, version, created_at, updated_at)
                VALUES (?, 'en', 'UTC', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "fixture-" + UUID.randomUUID());
    }

    public long config(long channelId, int revisionNo) {
        return insert("""
                INSERT INTO channel_config (channel_id, revision_no, default_content_type, shorts_min_seconds,
                       shorts_max_seconds, longform_min_seconds, longform_max_seconds, synthetic_disclosure_default,
                       created_by, created_at)
                VALUES (?, ?, 'SHORTS', 10, 60, 60, 600, TRUE, ?, UTC_TIMESTAMP(6))
                """, channelId, revisionNo, admin());
    }

    public long topic(long channelId) {
        return topic(channelId, hash("topic-" + SEQUENCE.incrementAndGet() + UUID.randomUUID()));
    }

    public long topic(long channelId, String normalizedHash) {
        return insert("""
                INSERT INTO topic (channel_id, title, normalized_title, normalized_hash, normalization_version,
                       created_by, created_at)
                VALUES (?, 'Topic', 'topic', ?, 1, ?, UTC_TIMESTAMP(6))
                """, channelId, normalizedHash, admin());
    }

    /** content 행만(포인터 NULL). */
    public long bareContent(long channelId, long topicId) {
        return insert("""
                INSERT INTO content (channel_id, topic_id, content_type, title, status, version, created_by,
                       created_at, updated_at)
                VALUES (?, ?, 'SHORTS', 'Title 1', 'DRAFT', 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, channelId, topicId, admin());
    }

    public long revision(long contentId, long channelId, long configId, int revisionNo, String title) {
        return insert("""
                INSERT INTO content_revision (content_id, channel_id, revision_no, channel_config_id, working_title,
                       brief, language, target_duration_seconds, revision_hash, hash_version, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'brief', 'en', 30, ?, 1, ?, UTC_TIMESTAMP(6))
                """, contentId, channelId, revisionNo, configId, title, hash("rev-" + UUID.randomUUID()), admin());
    }

    /** 생성 계약대로 content → revision 1 → 포인터 설정. */
    public Created content(long channelId, long topicId, long configId) {
        long contentId = bareContent(channelId, topicId);
        long revisionId = revision(contentId, channelId, configId, 1, "Title 1");
        jdbc.update("UPDATE content SET current_revision_id = ?, title = 'Title 1' WHERE id = ?", revisionId, contentId);
        return new Created(contentId, revisionId);
    }

    public record Created(long contentId, long revisionId) {
    }

    /** 채널·설정·topic·콘텐츠 한 벌. */
    public Graph graph() {
        long channel = channel();
        long config = config(channel, 1);
        long topic = topic(channel);
        Created content = content(channel, topic, config);
        return new Graph(channel, config, topic, content.contentId(), content.revisionId());
    }

    public record Graph(long channelId, long configId, long topicId, long contentId, long revisionId) {
    }

    public long run(long contentId, long revisionId, int runNo) {
        return insert("""
                INSERT INTO content_run (content_id, content_revision_id, run_no, pipeline_version, execution_mode,
                       status, requested_by, created_at)
                VALUES (?, ?, ?, 'fake-v1', 'FAKE', 'QUEUED', ?, UTC_TIMESTAMP(6))
                """, contentId, revisionId, runNo, admin());
    }

    /** 활성 run 설정(Q0 계약 순서: run INSERT 후 content 포인터·상태). */
    public long activate(long contentId, long revisionId, int runNo) {
        long runId = run(contentId, revisionId, runNo);
        jdbc.update("UPDATE content SET status = 'PRODUCING', active_run_id = ?, version = version + 1 WHERE id = ?",
                runId, contentId);
        return runId;
    }

    /** R10 계약 순서: 포인터 해제 → run 종료(같은 TX는 호출자가 보장). */
    public void finish(long contentId, long runId, String status) {
        jdbc.update("UPDATE content SET status = 'DRAFT', active_run_id = NULL, version = version + 1 WHERE id = ?",
                contentId);
        jdbc.update("UPDATE content_run SET status = 'RUNNING', started_at = UTC_TIMESTAMP(6) WHERE id = ?"
                + " AND status = 'QUEUED'", runId);
        jdbc.update("UPDATE content_run SET status = ?, finished_at = UTC_TIMESTAMP(6) WHERE id = ?", status, runId);
    }
}
