-- V7__create_queue_engine (T03-C, FD-08/09/10/11, ADR-067~076). 설계: docs/plans/t03-final/T03_FINAL_DATABASE.md,
-- T03_SCHEMA_DRAFT.sql(큐 범위), T03_FINAL_STATE_MACHINE.md, T03_FINAL_LOCK_TRANSACTION.md. 적용 후 수정 금지(ADR-009).
-- 비용·Operation(job_operation, intent, dispatch, evidence, receipt)은 T03-D(V8 이후) 범위라 만들지 않는다.
-- queue_command_log는 V6에서 생성됐으므로 재사용한다. CHARSET/COLLATE 생략으로 DB 기본 utf8mb4_unicode_ci 상속.
-- 생성 순서(FV-01): queue_control → queue_worker_instance → automation_job → job_attempt → 현재 attempt 역참조 FK
--   → job_dependency → job_event → 트리거.

-- 1) 큐 전역 제어(단일 행). dispatch_enabled는 새 claim 허용 여부이며 지출 허용(budget_control)과 독립이다
CREATE TABLE queue_control (
    id                  TINYINT      NOT NULL,
    dispatch_enabled    BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '새 claim 허용. 지출 허용(budget_control)과 독립',
    max_running_jobs    SMALLINT     NOT NULL DEFAULT 2 COMMENT 'DB 전역 RUNNING(유효 lease) 상한(FD-11)',
    dispatch_changed_at DATETIME(6)  NULL,
    dispatch_changed_by BIGINT       NULL,
    dispatch_reason     VARCHAR(500) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_queue_control PRIMARY KEY (id),
    CONSTRAINT ck_queue_control_singleton CHECK (id = 1),
    -- 2 초과는 ADR 필요(FD-11)
    CONSTRAINT ck_queue_control_max CHECK (max_running_jobs BETWEEN 1 AND 2),
    CONSTRAINT ck_queue_control_change CHECK ((dispatch_changed_at IS NULL) = (dispatch_changed_by IS NULL)),
    CONSTRAINT fk_queue_control_by FOREIGN KEY (dispatch_changed_by) REFERENCES admin_user (id)
);
INSERT INTO queue_control (id, dispatch_enabled, max_running_jobs, version, updated_at)
VALUES (1, TRUE, 2, 0, UTC_TIMESTAMP(6));

-- 2) 프로세스 기동 단위 worker 인스턴스. job_attempt FK 대상이므로 job_attempt보다 먼저 만든다(FV-01)
CREATE TABLE queue_worker_instance (
    instance_id    CHAR(36)     COLLATE utf8mb4_bin NOT NULL COMMENT '프로세스 기동마다 UUID(소문자)',
    host_label     VARCHAR(100) NOT NULL COMMENT '호스트명 등 진단용(비밀 아님)',
    worker_threads SMALLINT     NOT NULL,
    started_at     DATETIME(6)  NOT NULL,
    last_seen_at   DATETIME(6)  NOT NULL,
    stopped_at     DATETIME(6)  NULL,
    CONSTRAINT pk_queue_worker_instance PRIMARY KEY (instance_id),
    CONSTRAINT ck_worker_instance_id CHECK (instance_id REGEXP
        '(?-i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_worker_threads CHECK (worker_threads BETWEEN 0 AND 8),
    CONSTRAINT ck_worker_seen CHECK (last_seen_at >= started_at AND (stopped_at IS NULL OR stopped_at >= started_at)),
    INDEX ix_worker_seen (stopped_at, last_seen_at)
);

-- 3) Run 안의 DAG 노드. channel/content/run 소유는 복합 FK로 강제하고 불변(트리거)
CREATE TABLE automation_job (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    run_id                    BIGINT       NOT NULL,
    content_id                BIGINT       NOT NULL COMMENT '불변 복제(상한·필터·비용 인터페이스)',
    channel_id                BIGINT       NOT NULL COMMENT '불변 복제',
    job_key                   VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    job_type                  VARCHAR(48)  NOT NULL,
    node_kind                 VARCHAR(16)  NOT NULL,
    spawned_by_job_id         BIGINT       NULL COMMENT '확장 자식이면 EXPANDER job. T03은 항상 NULL',
    requires_operation        BOOLEAN      NOT NULL COMMENT '외부 부작용(비용) 있는 job. job_operation 필수(T03-D)',
    status                    VARCHAR(24)  NOT NULL,
    status_reason             VARCHAR(64)  NULL,
    available_at              DATETIME(6)  NOT NULL COMMENT 'READY/RETRY_WAIT claim 가능 시각(DB 시각)',
    attempt_count             INT          NOT NULL DEFAULT 0 COMMENT '모든 claim 수 = 마지막 attempt_no',
    auto_attempts_used        SMALLINT     NOT NULL DEFAULT 0,
    max_auto_attempts         SMALLINT     NOT NULL COMMENT 'pipeline 정의에서 복사, 불변. 기본 3(최초 포함, FD-09)',
    manual_resumes_used       SMALLINT     NOT NULL DEFAULT 0,
    max_manual_resumes        SMALLINT     NOT NULL COMMENT 'pipeline 정의에서 복사, 불변. 기본 2(FD-10)',
    pending_manual_resume     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '다음 claim을 수동 시도로 계산',
    execution_timeout_seconds INT          NOT NULL COMMENT 'attempt.execution_deadline_at 계산, 불변',
    current_attempt_no        INT          NULL,
    lease_owner               VARCHAR(100) NULL COMMENT '<instance_id>:<worker_slot>',
    lease_token               CHAR(36)     COLLATE utf8mb4_bin NULL,
    lease_expires_at          DATETIME(6)  NULL,
    heartbeat_at              DATETIME(6)  NULL,
    cancel_requested_at       DATETIME(6)  NULL,
    input_hash                CHAR(64)     COLLATE utf8mb4_bin NULL COMMENT '재사용 조회용(T05). unique 아님',
    result_summary_json       JSON         NULL COMMENT '안전한 요약만. 한 번 기록 후 불변',
    error_code                VARCHAR(64)  NULL,
    error_message             VARCHAR(500) NULL COMMENT '비밀·원문 응답 제외한 안전 메시지',
    created_at               DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    finished_at               DATETIME(6)  NULL,
    CONSTRAINT pk_automation_job PRIMARY KEY (id),
    CONSTRAINT uk_job_key UNIQUE (run_id, job_key),
    CONSTRAINT uk_job_owner UNIQUE (run_id, id),
    -- T03-D 참조용 소유권 연쇄 대체 키(channel/content/run/job)
    CONSTRAINT uk_job_owner_chain UNIQUE (id, run_id, content_id, channel_id),
    CONSTRAINT fk_job_run FOREIGN KEY (content_id, run_id) REFERENCES content_run (content_id, id),
    CONSTRAINT fk_job_content FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
    CONSTRAINT fk_job_spawned_by FOREIGN KEY (run_id, spawned_by_job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT ck_job_status CHECK (status IN ('WAITING', 'READY', 'RUNNING', 'RETRY_WAIT', 'ACTION_REQUIRED',
        'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_job_key CHECK (job_key REGEXP '(?-i)^[a-z0-9][a-z0-9:/._-]{0,127}$'),
    CONSTRAINT ck_job_type CHECK (job_type REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,47}$'),
    CONSTRAINT ck_job_node CHECK (node_kind IN ('TASK', 'EXPANDER', 'BARRIER')),
    CONSTRAINT ck_job_child CHECK (spawned_by_job_id IS NULL OR node_kind = 'TASK'),
    CONSTRAINT ck_job_reason CHECK (status_reason IS NULL OR status_reason REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_job_lease CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL)
        AND (lease_token IS NULL) = (lease_owner IS NULL)
        AND (lease_token IS NULL) = (lease_expires_at IS NULL)
        AND (lease_token IS NULL OR current_attempt_no IS NOT NULL)),
    CONSTRAINT ck_job_token CHECK (lease_token IS NULL OR lease_token REGEXP
        '(?-i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_job_finished CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'))
        = (finished_at IS NOT NULL)),
    CONSTRAINT ck_job_counts CHECK (max_auto_attempts BETWEEN 1 AND 10 AND max_manual_resumes BETWEEN 0 AND 10
        AND auto_attempts_used BETWEEN 0 AND max_auto_attempts
        AND manual_resumes_used BETWEEN 0 AND max_manual_resumes
        AND attempt_count >= auto_attempts_used
        AND (current_attempt_no IS NULL OR current_attempt_no = attempt_count)),
    CONSTRAINT ck_job_pending_resume CHECK (pending_manual_resume = FALSE OR status = 'READY'),
    CONSTRAINT ck_job_cancel CHECK (cancel_requested_at IS NULL
        OR status IN ('RUNNING', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    -- FAKE 전용 T03: RECOVERY_REQUIRED는 외부 효과(Operation)가 있는 job에만 허용
    CONSTRAINT ck_job_recovery CHECK (status <> 'RECOVERY_REQUIRED' OR requires_operation = TRUE),
    CONSTRAINT ck_job_timeout CHECK (execution_timeout_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_job_hash CHECK (input_hash IS NULL OR input_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    INDEX ix_job_claim (status, available_at, id),
    INDEX ix_job_lease (status, lease_expires_at, id),
    INDEX ix_job_run (run_id, status, id),
    INDEX ix_job_channel (channel_id, status, id),
    INDEX ix_job_reuse (job_type, input_hash)
);

-- 4) job의 lease 1회(실행 1회). 종료 시 한 번만 갱신
CREATE TABLE job_attempt (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    job_id                BIGINT       NOT NULL,
    attempt_no            INT          NOT NULL,
    trigger_kind          VARCHAR(8)   NOT NULL COMMENT 'AUTO | MANUAL',
    worker_instance_id    CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    lease_owner           VARCHAR(100) NOT NULL,
    lease_token           CHAR(36)     COLLATE utf8mb4_bin NOT NULL,
    started_at            DATETIME(6)  NOT NULL,
    execution_deadline_at DATETIME(6)  NOT NULL,
    finished_at           DATETIME(6)  NULL,
    outcome               VARCHAR(24)  NULL,
    error_code            VARCHAR(64)  NULL,
    CONSTRAINT pk_job_attempt PRIMARY KEY (id),
    CONSTRAINT uk_attempt_no UNIQUE (job_id, attempt_no),
    CONSTRAINT uk_attempt_owner UNIQUE (job_id, id),
    CONSTRAINT uk_attempt_token UNIQUE (lease_token),
    CONSTRAINT fk_attempt_job FOREIGN KEY (job_id) REFERENCES automation_job (id),
    CONSTRAINT fk_attempt_instance FOREIGN KEY (worker_instance_id) REFERENCES queue_worker_instance (instance_id),
    CONSTRAINT ck_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT ck_attempt_trigger CHECK (trigger_kind IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_attempt_token CHECK (lease_token REGEXP
        '(?-i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_attempt_deadline CHECK (execution_deadline_at > started_at),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED', 'RETRYABLE_ERROR',
        'ACTION_REQUIRED', 'FATAL_ERROR', 'OUTCOME_UNKNOWN', 'LEASE_EXPIRED', 'DEADLINE_EXCEEDED',
        'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT ck_attempt_finished CHECK ((finished_at IS NULL) = (outcome IS NULL))
);

-- 5) 현재 attempt 역참조: job_attempt 생성 후 추가
ALTER TABLE automation_job
    ADD CONSTRAINT fk_job_current_attempt FOREIGN KEY (id, current_attempt_no)
        REFERENCES job_attempt (job_id, attempt_no);

-- 6) DAG 간선. 같은 run 안의 두 job만(복합 FK), 자기 간선 금지, 후행은 WAITING일 때만 추가(트리거)
CREATE TABLE job_dependency (
    run_id             BIGINT NOT NULL,
    job_id             BIGINT NOT NULL COMMENT '후행',
    predecessor_job_id BIGINT NOT NULL COMMENT '선행',
    CONSTRAINT pk_job_dependency PRIMARY KEY (job_id, predecessor_job_id),
    CONSTRAINT fk_dep_job FOREIGN KEY (run_id, job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT fk_dep_predecessor FOREIGN KEY (run_id, predecessor_job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT ck_dep_no_self CHECK (job_id <> predecessor_job_id),
    INDEX ix_dep_predecessor (predecessor_job_id, job_id),
    INDEX ix_dep_run (run_id)
);

-- 7) 상태 전이·명령 이력(append-only)
CREATE TABLE job_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    run_id        BIGINT       NOT NULL,
    job_id        BIGINT       NULL COMMENT 'NULL = run 수준 이벤트',
    attempt_id    BIGINT       NULL,
    event_type    VARCHAR(40)  NOT NULL,
    from_status   VARCHAR(24)  NULL,
    to_status     VARCHAR(24)  NULL,
    reason        VARCHAR(64)  NULL,
    actor_type    VARCHAR(16)  NOT NULL,
    actor_user_id BIGINT       NULL,
    request_key   VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '관리자 명령의 Idempotency-Key',
    trace_id      VARCHAR(64)  NULL,
    details_json  JSON         NULL COMMENT '비밀·원문 금지',
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_job_event PRIMARY KEY (id),
    CONSTRAINT fk_event_run FOREIGN KEY (run_id) REFERENCES content_run (id),
    CONSTRAINT fk_event_job FOREIGN KEY (run_id, job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT fk_event_attempt FOREIGN KEY (job_id, attempt_id) REFERENCES job_attempt (job_id, id),
    CONSTRAINT fk_event_actor FOREIGN KEY (actor_user_id) REFERENCES admin_user (id),
    CONSTRAINT ck_event_type CHECK (event_type REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_event_actor CHECK (actor_type IN ('ADMIN', 'SYSTEM', 'WORKER')),
    CONSTRAINT ck_event_admin CHECK ((actor_type = 'ADMIN') = (actor_user_id IS NOT NULL)),
    CONSTRAINT ck_event_attempt CHECK (attempt_id IS NULL OR job_id IS NOT NULL),
    INDEX ix_event_run (run_id, id),
    INDEX ix_event_job (job_id, id)
);

-- 8) 불변·상태 보호 트리거(FV-02). 운영 앱 권한과 이중 방어.
--    INSERT 트리거에서 다른 테이블을 조회하면 INSERT 문 안의 S 잠금이 되므로(T03-B-FIX 실측) job_dependency의
--    설계 승인된 후행 상태 확인 외에는 두지 않는다. 그 확인은 같은 TX가 이미 X로 보유한 job 행을 읽는다.
CREATE TRIGGER trg_queue_control_no_delete BEFORE DELETE ON queue_control FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'queue_control cannot be deleted';
CREATE TRIGGER trg_worker_instance_no_delete BEFORE DELETE ON queue_worker_instance FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'queue_worker_instance cannot be deleted';
CREATE TRIGGER trg_automation_job_no_delete BEFORE DELETE ON automation_job FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job cannot be deleted';
CREATE TRIGGER trg_job_attempt_no_delete BEFORE DELETE ON job_attempt FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_attempt cannot be deleted';
CREATE TRIGGER trg_job_dependency_no_update BEFORE UPDATE ON job_dependency FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_dependency is immutable';
CREATE TRIGGER trg_job_dependency_no_delete BEFORE DELETE ON job_dependency FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_dependency is immutable';
CREATE TRIGGER trg_job_event_no_update BEFORE UPDATE ON job_event FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_event is append-only';
CREATE TRIGGER trg_job_event_no_delete BEFORE DELETE ON job_event FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_event is append-only';

DELIMITER $$

CREATE TRIGGER trg_worker_instance_before_update BEFORE UPDATE ON queue_worker_instance FOR EACH ROW
BEGIN
    IF NOT (BINARY NEW.instance_id <=> BINARY OLD.instance_id AND BINARY NEW.host_label <=> BINARY OLD.host_label
            AND NEW.worker_threads <=> OLD.worker_threads AND NEW.started_at <=> OLD.started_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'queue_worker_instance identity is immutable';
    END IF;
    IF OLD.stopped_at IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stopped queue_worker_instance is immutable';
    END IF;
END$$

-- 새 job은 WAITING·카운터 0·lease 없음으로만 생성(J01). 승격은 같은 TX의 UPDATE로 한다
CREATE TRIGGER trg_automation_job_before_insert BEFORE INSERT ON automation_job FOR EACH ROW
BEGIN
    IF NEW.status <> 'WAITING' OR NEW.attempt_count <> 0 OR NEW.auto_attempts_used <> 0
            OR NEW.manual_resumes_used <> 0 OR NEW.pending_manual_resume OR NEW.current_attempt_no IS NOT NULL
            OR NEW.lease_token IS NOT NULL OR NEW.cancel_requested_at IS NOT NULL
            OR NEW.result_summary_json IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job must be created as a fresh WAITING job';
    END IF;
END$$

CREATE TRIGGER trg_automation_job_before_update BEFORE UPDATE ON automation_job FOR EACH ROW
BEGIN
    IF OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'terminal automation_job is immutable';
    END IF;
    IF NOT (NEW.id <=> OLD.id AND NEW.run_id <=> OLD.run_id AND NEW.content_id <=> OLD.content_id
            AND NEW.channel_id <=> OLD.channel_id AND BINARY NEW.job_key <=> BINARY OLD.job_key
            AND BINARY NEW.job_type <=> BINARY OLD.job_type AND BINARY NEW.node_kind <=> BINARY OLD.node_kind
            AND NEW.spawned_by_job_id <=> OLD.spawned_by_job_id AND NEW.requires_operation <=> OLD.requires_operation
            AND NEW.max_auto_attempts <=> OLD.max_auto_attempts AND NEW.max_manual_resumes <=> OLD.max_manual_resumes
            AND NEW.execution_timeout_seconds <=> OLD.execution_timeout_seconds
            AND BINARY NEW.input_hash <=> BINARY OLD.input_hash AND NEW.created_at <=> OLD.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job identity columns are immutable';
    END IF;
    IF OLD.result_summary_json IS NOT NULL AND NOT (NEW.result_summary_json <=> OLD.result_summary_json) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job result summary is immutable';
    END IF;
    -- 시도·재개 카운터는 감소하지 않는다(FD-09/10)
    IF NEW.attempt_count < OLD.attempt_count OR NEW.auto_attempts_used < OLD.auto_attempts_used
            OR NEW.manual_resumes_used < OLD.manual_resumes_used THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job attempt counters cannot decrease';
    END IF;
    -- 취소 의도는 한 번 기록되면 바뀌지 않는다
    IF OLD.cancel_requested_at IS NOT NULL AND NOT (NEW.cancel_requested_at <=> OLD.cancel_requested_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job cancel intent is immutable';
    END IF;
    -- WAITING으로 되돌아가지 않는다(승격·실행된 노드에 간선이 추가되지 않게)
    IF NEW.status = 'WAITING' AND OLD.status <> 'WAITING' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'automation_job cannot return to WAITING';
    END IF;
END$$

-- 이미 승격·실행된 노드로 들어가는 간선 추가 금지(확장 계약의 DB 방어선). 후행 job 행은 같은 TX가 보유한다
CREATE TRIGGER trg_job_dependency_before_insert BEFORE INSERT ON job_dependency FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM automation_job j WHERE j.id = NEW.job_id AND j.status = 'WAITING') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'dependencies can only target WAITING jobs';
    END IF;
END$$

CREATE TRIGGER trg_job_attempt_before_insert BEFORE INSERT ON job_attempt FOR EACH ROW
BEGIN
    IF NEW.finished_at IS NOT NULL OR NEW.outcome IS NOT NULL OR NEW.error_code IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_attempt must be created open';
    END IF;
END$$

CREATE TRIGGER trg_job_attempt_before_update BEFORE UPDATE ON job_attempt FOR EACH ROW
BEGIN
    IF OLD.finished_at IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'finished job_attempt is immutable';
    END IF;
    IF NOT (NEW.id <=> OLD.id AND NEW.job_id <=> OLD.job_id AND NEW.attempt_no <=> OLD.attempt_no
            AND BINARY NEW.trigger_kind <=> BINARY OLD.trigger_kind
            AND BINARY NEW.worker_instance_id <=> BINARY OLD.worker_instance_id
            AND BINARY NEW.lease_owner <=> BINARY OLD.lease_owner AND BINARY NEW.lease_token <=> BINARY OLD.lease_token
            AND NEW.started_at <=> OLD.started_at AND NEW.execution_deadline_at <=> OLD.execution_deadline_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'job_attempt identity columns are immutable';
    END IF;
END$$

DELIMITER ;
