package com.aistudio.queue;

import java.sql.Statement;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.aistudio.content.ContentFixtures;

/** V7 스키마 직접 검증용 SQL fixture(서비스를 거치지 않고 DB 제약·트리거만 확인). */
public final class QueueFixtures {

    private final JdbcTemplate jdbc;
    private final ContentFixtures content;

    public QueueFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.content = new ContentFixtures(jdbc);
    }

    public ContentFixtures content() {
        return content;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public record RunGraph(long channelId, long contentId, long revisionId, long runId) {
    }

    /** 채널·콘텐츠·revision 1·활성 QUEUED run. */
    public RunGraph run() {
        ContentFixtures.Graph g = content.graph();
        long runId = content.activate(g.contentId(), g.revisionId(), 1);
        return new RunGraph(g.channelId(), g.contentId(), g.revisionId(), runId);
    }

    public String instance() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO queue_worker_instance (instance_id, host_label, worker_threads, started_at, last_seen_at)
                VALUES (?, 'fixture', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", id);
        return id;
    }

    public long job(RunGraph r, String key) {
        return job(r.runId(), r.contentId(), r.channelId(), key, false);
    }

    public long job(long runId, long contentId, long channelId, String key, boolean requiresOperation) {
        return insert("""
                INSERT INTO automation_job (run_id, content_id, channel_id, job_key, job_type, node_kind,
                       requires_operation, status, available_at, max_auto_attempts, max_manual_resumes,
                       execution_timeout_seconds, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'FAKE_TASK', 'TASK', ?, 'WAITING', UTC_TIMESTAMP(6), 3, 2, 60,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", runId, contentId, channelId, key, requiresOperation);
    }

    public void ready(long jobId) {
        jdbc.update("UPDATE automation_job SET status = 'READY', updated_at = UTC_TIMESTAMP(6) WHERE id = ?", jobId);
    }

    public record Claim(long attemptId, int attemptNo, String token) {
    }

    /** READY job을 claim 계약 순서(attempt INSERT → job RUNNING·현재 attempt)로 RUNNING으로 만든다. */
    public Claim claim(long jobId, String instanceId) {
        int no = jdbc.queryForObject("SELECT attempt_count + 1 FROM automation_job WHERE id = ?", Integer.class, jobId);
        String token = UUID.randomUUID().toString();
        long attemptId = insert("""
                INSERT INTO job_attempt (job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
                       started_at, execution_deadline_at)
                VALUES (?, ?, 'AUTO', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 60 SECOND)""", jobId, no,
                instanceId, instanceId + ":0", token);
        jdbc.update("""
                UPDATE automation_job SET status = 'RUNNING', attempt_count = ?, auto_attempts_used = auto_attempts_used + 1,
                       current_attempt_no = ?, lease_owner = ?, lease_token = ?,
                       lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL 60 SECOND, heartbeat_at = UTC_TIMESTAMP(6),
                       updated_at = UTC_TIMESTAMP(6)
                 WHERE id = ?""", no, no, instanceId + ":0", token, jobId);
        return new Claim(attemptId, no, token);
    }

    /** RUNNING job 성공 종료(attempt 종료 → job SUCCEEDED, lease 해제). */
    public void succeed(long jobId, Claim c) {
        jdbc.update("UPDATE job_attempt SET finished_at = UTC_TIMESTAMP(6), outcome = 'SUCCEEDED' WHERE id = ?",
                c.attemptId());
        jdbc.update("""
                UPDATE automation_job SET status = 'SUCCEEDED', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, finished_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
                 WHERE id = ?""", jobId);
    }

    public void dependency(long runId, long jobId, long predecessorId) {
        jdbc.update("INSERT INTO job_dependency (run_id, job_id, predecessor_job_id) VALUES (?, ?, ?)", runId, jobId,
                predecessorId);
    }

    public long event(long runId, Long jobId, Long attemptId, String type) {
        return insert("""
                INSERT INTO job_event (run_id, job_id, attempt_id, event_type, actor_type, created_at)
                VALUES (?, ?, ?, ?, 'SYSTEM', UTC_TIMESTAMP(6))""", runId, jobId, attemptId, type);
    }
}
