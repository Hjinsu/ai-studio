package com.aistudio.queue;

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
 * V7 큐 스키마 DB 계약(FV-01/02/04): 생성 순서, 소유권 복합 FK, 불변·상태 트리거, CHECK. SQL로 위반을 시도해 DB가
 * 최종 방어하는지 확인한다. 서비스 경로 검증은 별도 IT. 격리 테스트 DB 전용.
 */
@Tag("integration")
class QueueSchemaIT {

    static final TestDatabases.TestDatabase DB = TestDatabases.create();
    static JdbcTemplate jdbc;
    static QueueFixtures fx;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(DB.dataSource()).locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate();
        jdbc = new JdbcTemplate(DB.dataSource());
        fx = new QueueFixtures(jdbc);
    }

    private static void rejected(String sql, String message, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).hasStackTraceContaining(message);
    }

    private static final String FK = "foreign key constraint fails";

    // ---------------------------------------------------------------- 구조·참조 키(C/D 공통)
    @Test
    void referenceKeysAndForeignKeysExist() {
        List<String> uniques = jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', constraint_name) FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE() AND constraint_type IN ('UNIQUE', 'PRIMARY KEY') ORDER BY 1""",
                String.class);
        assertThat(uniques).contains("automation_job.PRIMARY", "automation_job.uk_job_key", "automation_job.uk_job_owner",
                "automation_job.uk_job_owner_chain", "job_attempt.PRIMARY", "job_attempt.uk_attempt_no",
                "job_attempt.uk_attempt_owner", "job_attempt.uk_attempt_token", "job_dependency.PRIMARY",
                "queue_control.PRIMARY", "queue_worker_instance.PRIMARY");
        List<String> keys = jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', constraint_name, '(', GROUP_CONCAT(column_name ORDER BY ordinal_position),
                       ')') FROM information_schema.key_column_usage
                 WHERE table_schema = DATABASE() AND referenced_table_name IS NULL
                   AND table_name IN ('automation_job', 'job_attempt')
                 GROUP BY table_name, constraint_name ORDER BY 1""", String.class);
        assertThat(keys).contains("automation_job.uk_job_owner(run_id,id)",
                "automation_job.uk_job_owner_chain(id,run_id,content_id,channel_id)",
                "job_attempt.uk_attempt_owner(job_id,id)", "job_attempt.uk_attempt_no(job_id,attempt_no)");
        List<String> fks = jdbc.queryForList("""
                SELECT CONCAT(table_name, '.', constraint_name, '(', GROUP_CONCAT(column_name ORDER BY ordinal_position),
                       ')->', MAX(referenced_table_name), '(',
                       GROUP_CONCAT(referenced_column_name ORDER BY ordinal_position), ')')
                  FROM information_schema.key_column_usage
                 WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL
                   AND table_name IN ('automation_job', 'job_attempt', 'job_dependency', 'job_event', 'queue_control')
                 GROUP BY table_name, constraint_name ORDER BY 1""", String.class);
        assertThat(fks).contains(
                "automation_job.fk_job_run(content_id,run_id)->content_run(content_id,id)",
                "automation_job.fk_job_content(content_id,channel_id)->content(id,channel_id)",
                "automation_job.fk_job_spawned_by(run_id,spawned_by_job_id)->automation_job(run_id,id)",
                "automation_job.fk_job_current_attempt(id,current_attempt_no)->job_attempt(job_id,attempt_no)",
                "job_attempt.fk_attempt_job(job_id)->automation_job(id)",
                "job_attempt.fk_attempt_instance(worker_instance_id)->queue_worker_instance(instance_id)",
                "job_dependency.fk_dep_job(run_id,job_id)->automation_job(run_id,id)",
                "job_dependency.fk_dep_predecessor(run_id,predecessor_job_id)->automation_job(run_id,id)",
                "job_event.fk_event_job(run_id,job_id)->automation_job(run_id,id)",
                "job_event.fk_event_attempt(job_id,attempt_id)->job_attempt(job_id,id)");
        Integer cascades = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE()
                   AND (delete_rule <> 'RESTRICT' OR update_rule <> 'RESTRICT')""", Integer.class);
        assertThat(cascades).isZero();
    }

    @Test
    void queueControlIsASeededSingletonWithLeaseCapTwo() {
        assertThat(jdbc.queryForMap("SELECT id, dispatch_enabled, max_running_jobs FROM queue_control"))
                .containsEntry("id", 1).containsEntry("dispatch_enabled", true);
        assertThat(jdbc.queryForObject("SELECT max_running_jobs FROM queue_control", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM queue_control", Integer.class)).isEqualTo(1);
        rejected("INSERT INTO queue_control (id, updated_at) VALUES (2, UTC_TIMESTAMP(6))", "ck_queue_control_singleton");
        rejected("UPDATE queue_control SET max_running_jobs = 3 WHERE id = 1", "ck_queue_control_max");
        rejected("UPDATE queue_control SET dispatch_changed_at = UTC_TIMESTAMP(6) WHERE id = 1",
                "ck_queue_control_change");
        rejected("DELETE FROM queue_control", "queue_control cannot be deleted");
    }

    // ---------------------------------------------------------------- 소유권
    @Test
    void jobOwnershipChainIsEnforced() {
        QueueFixtures.RunGraph a = fx.run();
        QueueFixtures.RunGraph b = fx.run();
        // 다른 콘텐츠의 run
        assertThatThrownBy(() -> fx.job(b.runId(), a.contentId(), a.channelId(), "x", false))
                .hasStackTraceContaining(FK);
        // 콘텐츠와 다른 채널
        assertThatThrownBy(() -> fx.job(a.runId(), a.contentId(), b.channelId(), "x", false))
                .hasStackTraceContaining(FK);
        long jobA = fx.job(a, "a");
        long jobB = fx.job(b, "b");
        // 다른 run의 job 사이 간선, 자기 간선
        rejected("INSERT INTO job_dependency (run_id, job_id, predecessor_job_id) VALUES (?, ?, ?)", FK, a.runId(),
                jobA, jobB);
        rejected("INSERT INTO job_dependency (run_id, job_id, predecessor_job_id) VALUES (?, ?, ?)", "ck_dep_no_self",
                a.runId(), jobA, jobA);
        // run_id 위조: 선행·후행이 같은 run이지만 다른 run_id를 적음
        long jobA2 = fx.job(a, "a2");
        rejected("INSERT INTO job_dependency (run_id, job_id, predecessor_job_id) VALUES (?, ?, ?)", FK, b.runId(),
                jobA2, jobA);
        // 다른 run의 job을 이벤트 job으로
        assertThatThrownBy(() -> fx.event(a.runId(), jobB, null, "X_EVENT")).hasStackTraceContaining(FK);
        // 확장 자식: 다른 run의 EXPANDER를 부모로 지정
        rejected("""
                INSERT INTO automation_job (run_id, content_id, channel_id, job_key, job_type, node_kind, spawned_by_job_id,
                       requires_operation, status, available_at, max_auto_attempts, max_manual_resumes,
                       execution_timeout_seconds, created_at, updated_at)
                VALUES (?, ?, ?, 'child', 'FAKE_TASK', 'TASK', ?, FALSE, 'WAITING', UTC_TIMESTAMP(6), 3, 2, 60,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", FK, a.runId(), a.contentId(), a.channelId(), jobB);
        rejected("INSERT INTO automation_job (run_id, content_id, channel_id, job_key, job_type, node_kind,"
                + " requires_operation, status, available_at, max_auto_attempts, max_manual_resumes,"
                + " execution_timeout_seconds, created_at, updated_at) VALUES (?, ?, ?, 'a', 'FAKE_TASK', 'TASK', FALSE,"
                + " 'WAITING', UTC_TIMESTAMP(6), 3, 2, 60, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", "uk_job_key",
                a.runId(), a.contentId(), a.channelId());
    }

    @Test
    void attemptOwnershipAndCurrentAttemptPointer() {
        QueueFixtures.RunGraph r = fx.run();
        long job = fx.job(r, "j");
        long other = fx.job(r, "k");
        fx.ready(job);
        String instance = fx.instance();
        // 존재하지 않는 worker 인스턴스
        rejected("""
                INSERT INTO job_attempt (job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
                       started_at, execution_deadline_at)
                VALUES (?, 1, 'AUTO', ?, 'x:0', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 60 SECOND)""", FK, job,
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString());
        // attempt 없이 현재 attempt 지정
        rejected("UPDATE automation_job SET current_attempt_no = 1, attempt_count = 1 WHERE id = ?", FK, job);
        QueueFixtures.Claim claim = fx.claim(job, instance);
        // 다른 job의 attempt를 이벤트에 연결
        assertThatThrownBy(() -> fx.event(r.runId(), other, claim.attemptId(), "X_EVENT")).hasStackTraceContaining(FK);
        assertThatCode(() -> fx.event(r.runId(), job, claim.attemptId(), "CLAIMED")).doesNotThrowAnyException();
        // 같은 job 같은 번호, 같은 토큰 재사용
        rejected("""
                INSERT INTO job_attempt (job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
                       started_at, execution_deadline_at)
                VALUES (?, 1, 'AUTO', ?, 'x:0', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 60 SECOND)""",
                "uk_attempt_no", job, instance, java.util.UUID.randomUUID().toString());
        rejected("""
                INSERT INTO job_attempt (job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
                       started_at, execution_deadline_at)
                VALUES (?, 1, 'AUTO', ?, 'x:0', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 60 SECOND)""",
                "uk_attempt_token", other, instance, claim.token());
        // 현재 attempt 번호는 attempt_count와 같아야 한다
        rejected("UPDATE automation_job SET attempt_count = 2 WHERE id = ?", "ck_job_counts", job);
    }

    // ---------------------------------------------------------------- CHECK
    @Test
    void leaseAndStateChecks() {
        QueueFixtures.RunGraph r = fx.run();
        long job = fx.job(r, "j");
        rejected("UPDATE automation_job SET status = 'RUNNING' WHERE id = ?", "ck_job_lease", job);
        rejected("UPDATE automation_job SET lease_token = ?, lease_owner = 'x:0',"
                + " lease_expires_at = UTC_TIMESTAMP(6) WHERE id = ?", "ck_job_lease", java.util.UUID.randomUUID()
                        .toString(), job);
        rejected("UPDATE automation_job SET status = 'RECOVERY_REQUIRED' WHERE id = ?", "ck_job_recovery", job);
        rejected("UPDATE automation_job SET pending_manual_resume = TRUE WHERE id = ?", "ck_job_pending_resume", job);
        rejected("UPDATE automation_job SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", "ck_job_cancel", job);
        rejected("UPDATE automation_job SET status = 'SUCCEEDED' WHERE id = ?", "ck_job_finished", job);
        rejected("UPDATE automation_job SET auto_attempts_used = 4 WHERE id = ?", "ck_job_counts", job);
        rejected("UPDATE automation_job SET manual_resumes_used = 3 WHERE id = ?", "ck_job_counts", job);
        rejected("UPDATE automation_job SET status = 'DONE' WHERE id = ?", "ck_job_status", job);
        rejected("UPDATE automation_job SET status_reason = 'bad reason' WHERE id = ?", "ck_job_reason", job);
        rejected("""
                INSERT INTO automation_job (run_id, content_id, channel_id, job_key, job_type, node_kind,
                       requires_operation, status, available_at, max_auto_attempts, max_manual_resumes,
                       execution_timeout_seconds, created_at, updated_at)
                VALUES (?, ?, ?, 'Bad Key', 'FAKE_TASK', 'TASK', FALSE, 'WAITING', UTC_TIMESTAMP(6), 3, 2, 60,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "ck_job_key", r.runId(), r.contentId(), r.channelId());
        // RECOVERY_REQUIRED는 operation job만(RUNNING에서 OUTCOME_UNKNOWN)
        long op = fx.job(r.runId(), r.contentId(), r.channelId(), "op", true);
        fx.ready(op);
        QueueFixtures.Claim c = fx.claim(op, fx.instance());
        assertThatCode(() -> jdbc.update("""
                UPDATE automation_job SET status = 'RECOVERY_REQUIRED', status_reason = 'EXTERNAL_OUTCOME_UNKNOWN',
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL WHERE id = ?""", op))
                .doesNotThrowAnyException();
        assertThat(c.attemptNo()).isEqualTo(1);
        // 이벤트 actor 규칙
        rejected("INSERT INTO job_event (run_id, event_type, actor_type, created_at) VALUES (?, 'X', 'ADMIN',"
                + " UTC_TIMESTAMP(6))", "ck_event", r.runId());
        rejected("INSERT INTO job_event (run_id, event_type, actor_type, created_at) VALUES (?, 'RUN_X', 'ADMIN',"
                + " UTC_TIMESTAMP(6))", "ck_event_admin", r.runId());
        rejected("INSERT INTO job_event (run_id, attempt_id, event_type, actor_type, created_at) VALUES (?, 1, 'RUN_X',"
                + " 'SYSTEM', UTC_TIMESTAMP(6))", "ck_event_attempt", r.runId());
    }

    @Test
    void attemptChecks() {
        QueueFixtures.RunGraph r = fx.run();
        long job = fx.job(r, "j");
        String instance = fx.instance();
        String insert = """
                INSERT INTO job_attempt (job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
                       started_at, execution_deadline_at, outcome, finished_at)
                VALUES (?, ?, ?, ?, 'x:0', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL ? SECOND, ?, ?)""";
        rejected(insert, "ck_attempt_deadline", job, 1, "AUTO", instance, java.util.UUID.randomUUID().toString(), 0,
                null, null);
        rejected(insert, "ck_attempt_trigger", job, 1, "CRON", instance, java.util.UUID.randomUUID().toString(), 60,
                null, null);
        rejected(insert, "ck_attempt_token", job, 1, "AUTO", instance, "ABCDEF00-0000-0000-0000-000000000000", 60,
                null, null);
        rejected(insert, "job_attempt must be created open", job, 1, "AUTO", instance,
                java.util.UUID.randomUUID().toString(), 60, "SUCCEEDED", java.time.LocalDateTime.now());
        rejected("""
                INSERT INTO queue_worker_instance (instance_id, host_label, worker_threads, started_at, last_seen_at)
                VALUES ('ABC', 'h', 2, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "ck_worker_instance_id");
        rejected("""
                INSERT INTO queue_worker_instance (instance_id, host_label, worker_threads, started_at, last_seen_at)
                VALUES (?, 'h', 9, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "ck_worker_threads",
                java.util.UUID.randomUUID().toString());
    }

    // ---------------------------------------------------------------- 트리거
    @Test
    void jobsAreCreatedFreshAndIdentityIsImmutable() {
        QueueFixtures.RunGraph r = fx.run();
        rejected("""
                INSERT INTO automation_job (run_id, content_id, channel_id, job_key, job_type, node_kind,
                       requires_operation, status, available_at, max_auto_attempts, max_manual_resumes,
                       execution_timeout_seconds, created_at, updated_at)
                VALUES (?, ?, ?, 'ready', 'FAKE_TASK', 'TASK', FALSE, 'READY', UTC_TIMESTAMP(6), 3, 2, 60,
                       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", "fresh WAITING job", r.runId(), r.contentId(),
                r.channelId());
        long job = fx.job(r, "j");
        for (String change : List.of("job_key = 'other'", "job_type = 'FAKE_OTHER'", "node_kind = 'BARRIER'",
                "requires_operation = TRUE", "max_auto_attempts = 5", "max_manual_resumes = 1",
                "execution_timeout_seconds = 61", "created_at = created_at - INTERVAL 1 SECOND")) {
            rejected("UPDATE automation_job SET " + change + " WHERE id = ?", "identity columns are immutable", job);
        }
        rejected("DELETE FROM automation_job WHERE id = ?", "automation_job cannot be deleted", job);
        fx.ready(job);
        rejected("UPDATE automation_job SET status = 'WAITING' WHERE id = ?", "cannot return to WAITING", job);
        QueueFixtures.Claim c = fx.claim(job, fx.instance());
        rejected("UPDATE automation_job SET auto_attempts_used = 0 WHERE id = ?", "counters cannot decrease", job);
        jdbc.update("UPDATE automation_job SET result_summary_json = '{\"ok\":true}' WHERE id = ?", job);
        rejected("UPDATE automation_job SET result_summary_json = '{\"ok\":false}' WHERE id = ?",
                "result summary is immutable", job);
        jdbc.update("UPDATE automation_job SET cancel_requested_at = UTC_TIMESTAMP(6) WHERE id = ?", job);
        rejected("UPDATE automation_job SET cancel_requested_at = cancel_requested_at + INTERVAL 1 SECOND WHERE id = ?",
                "cancel intent is immutable", job);
        fx.succeed(job, c);
        rejected("UPDATE automation_job SET updated_at = UTC_TIMESTAMP(6) WHERE id = ?",
                "terminal automation_job is immutable", job);
    }

    @Test
    void dependenciesOnlyTargetWaitingJobsAndAreImmutable() {
        QueueFixtures.RunGraph r = fx.run();
        long a = fx.job(r, "a");
        long b = fx.job(r, "b");
        long c = fx.job(r, "c");
        fx.dependency(r.runId(), b, a);
        rejected("INSERT INTO job_dependency (run_id, job_id, predecessor_job_id) VALUES (?, ?, ?)",
                "Duplicate entry", r.runId(), b, a);
        fx.ready(c);
        rejected("INSERT INTO job_dependency (run_id, job_id, predecessor_job_id) VALUES (?, ?, ?)",
                "dependencies can only target WAITING jobs", r.runId(), c, a);
        rejected("UPDATE job_dependency SET predecessor_job_id = ? WHERE job_id = ?", "job_dependency is immutable", c,
                b);
        rejected("DELETE FROM job_dependency WHERE job_id = ?", "job_dependency is immutable", b);
    }

    @Test
    void attemptsEventsAndInstancesAreProtected() {
        QueueFixtures.RunGraph r = fx.run();
        long job = fx.job(r, "j");
        fx.ready(job);
        String instance = fx.instance();
        QueueFixtures.Claim c = fx.claim(job, instance);
        for (String change : List.of("attempt_no = 9", "trigger_kind = 'MANUAL'", "lease_owner = 'y:1'",
                "execution_deadline_at = execution_deadline_at + INTERVAL 1 SECOND")) {
            rejected("UPDATE job_attempt SET " + change + " WHERE id = ?", "job_attempt identity columns are immutable",
                    c.attemptId());
        }
        rejected("UPDATE job_attempt SET outcome = 'SUCCEEDED' WHERE id = ?", "ck_attempt_finished", c.attemptId());
        fx.succeed(job, c);
        rejected("UPDATE job_attempt SET error_code = 'X' WHERE id = ?", "finished job_attempt is immutable",
                c.attemptId());
        rejected("DELETE FROM job_attempt WHERE id = ?", "job_attempt cannot be deleted", c.attemptId());
        long event = fx.event(r.runId(), job, c.attemptId(), "JOB_SUCCEEDED");
        rejected("UPDATE job_event SET reason = 'X' WHERE id = ?", "job_event is append-only", event);
        rejected("DELETE FROM job_event WHERE id = ?", "job_event is append-only", event);
        rejected("UPDATE queue_worker_instance SET host_label = 'other' WHERE instance_id = ?",
                "identity is immutable", instance);
        jdbc.update("UPDATE queue_worker_instance SET last_seen_at = UTC_TIMESTAMP(6) WHERE instance_id = ?",
                instance);
        jdbc.update("UPDATE queue_worker_instance SET stopped_at = UTC_TIMESTAMP(6) WHERE instance_id = ?", instance);
        rejected("UPDATE queue_worker_instance SET last_seen_at = UTC_TIMESTAMP(6) WHERE instance_id = ?",
                "stopped queue_worker_instance is immutable", instance);
        rejected("DELETE FROM queue_worker_instance WHERE instance_id = ?", "cannot be deleted", instance);
    }
}
