-- Review only. T03-A6 domain and queue draft based on A3.

-- revision → channel_config 소유권 복합 FK 대상 (V4는 (channel_id, revision_no)만 UNIQUE)
ALTER TABLE channel_config ADD CONSTRAINT uk_channel_config_owner UNIQUE (channel_id, id);

CREATE TABLE topic (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id            BIGINT       NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    normalized_title      VARCHAR(255) NOT NULL COMMENT 'NFKC → 소문자(Locale.ROOT) → 공백 1칸 압축 → trim',
    normalized_hash       CHAR(64)     COLLATE utf8mb4_bin NOT NULL COMMENT 'SHA-256(normalization_version || normalized_title)',
    normalization_version SMALLINT     NOT NULL,
    note                  VARCHAR(500) NULL,
    created_by            BIGINT       NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_topic PRIMARY KEY (id),
    CONSTRAINT uk_topic_normalized UNIQUE (channel_id, normalized_hash),
    CONSTRAINT uk_topic_owner UNIQUE (id, channel_id),
    CONSTRAINT fk_topic_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT fk_topic_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_topic_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_topic_hash CHECK (normalized_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_topic_norm_version CHECK (normalization_version >= 1),
    INDEX ix_topic_channel (channel_id, id)
);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL 'topic is immutable'

CREATE TABLE content (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id          BIGINT       NOT NULL,
    topic_id            BIGINT       NOT NULL,
    content_type        VARCHAR(16)  NOT NULL,
    title               VARCHAR(255) NOT NULL COMMENT '현재 revision working_title 복제본(§2.3)',
    status              VARCHAR(16)  NOT NULL,
    current_revision_id BIGINT       NULL,
    active_run_id       BIGINT       NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          BIGINT       NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    archived_at         DATETIME(6)  NULL,
    CONSTRAINT pk_content PRIMARY KEY (id),
    CONSTRAINT uk_content_channel UNIQUE (id, channel_id),
    CONSTRAINT uk_content_type UNIQUE (id, content_type),
    CONSTRAINT fk_content_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT fk_content_topic FOREIGN KEY (topic_id, channel_id) REFERENCES topic (id, channel_id),
    CONSTRAINT fk_content_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_content_type CHECK (content_type IN ('SHORTS', 'LONGFORM')),
    CONSTRAINT ck_content_status CHECK (status IN ('DRAFT', 'PRODUCING', 'ARCHIVED')),
    CONSTRAINT ck_content_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    CONSTRAINT ck_content_active_run CHECK ((status = 'PRODUCING') = (active_run_id IS NOT NULL)),
    CONSTRAINT ck_content_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    INDEX ix_content_list (channel_id, status, id),
    INDEX ix_content_topic (topic_id, id),
    INDEX ix_content_status (status, id)
);
-- content_revision·content_run 생성 후 추가(순환 FK):
-- ALTER TABLE content ADD CONSTRAINT fk_content_current_revision
--     FOREIGN KEY (id, current_revision_id) REFERENCES content_revision (content_id, id);
-- ALTER TABLE content ADD CONSTRAINT fk_content_active_run
--     FOREIGN KEY (id, active_run_id) REFERENCES content_run (content_id, id);
-- 트리거:
--   BEFORE UPDATE: channel_id, topic_id, content_type, created_by, created_at 변경 시 SIGNAL;
--                  OLD.status = 'ARCHIVED'이면 SIGNAL (보관은 종료 상태)
--   BEFORE DELETE: SIGNAL

CREATE TABLE content_revision (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    content_id              BIGINT       NOT NULL,
    channel_id              BIGINT       NOT NULL COMMENT 'content.channel_id 복제(복합 FK용, 불변)',
    revision_no             INT          NOT NULL,
    channel_config_id       BIGINT       NOT NULL COMMENT '이 revision이 고정한 채널 설정 revision',
    base_run_id             BIGINT       NULL COMMENT '편집 기준 run(같은 콘텐츠, SUCCEEDED). 재사용은 T05',
    working_title           VARCHAR(255) NOT NULL,
    brief                   TEXT         NOT NULL COMMENT '제작 지시문(초안의 topic TEXT)',
    language                VARCHAR(16)  NOT NULL,
    target_duration_seconds INT          NOT NULL,
    settings_json           JSON         NULL COMMENT '비핵심 옵션만(ADR-015). 앱에서 허용 키·16KB 제한',
    revision_hash           CHAR(64)     COLLATE utf8mb4_bin NOT NULL,
    hash_version            SMALLINT     NOT NULL,
    change_reason           VARCHAR(500) NULL,
    created_by              BIGINT       NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    CONSTRAINT pk_content_revision PRIMARY KEY (id),
    CONSTRAINT uk_content_revision_no UNIQUE (content_id, revision_no),
    CONSTRAINT uk_content_revision_owner UNIQUE (content_id, id),
    CONSTRAINT fk_revision_content FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
    CONSTRAINT fk_revision_channel_config FOREIGN KEY (channel_id, channel_config_id)
        REFERENCES channel_config (channel_id, id),
    CONSTRAINT fk_revision_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_revision_no CHECK (revision_no >= 1),
    CONSTRAINT ck_revision_language CHECK (language REGEXP '(?-i)^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_revision_duration CHECK (target_duration_seconds BETWEEN 1 AND 14400),
    CONSTRAINT ck_revision_title CHECK (CHAR_LENGTH(TRIM(working_title)) > 0),
    CONSTRAINT ck_revision_brief CHECK (CHAR_LENGTH(TRIM(brief)) > 0),
    CONSTRAINT ck_revision_hash CHECK (revision_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_revision_hash_version CHECK (hash_version >= 1)
);
-- content_run 생성 후 추가:
-- ALTER TABLE content_revision ADD CONSTRAINT fk_revision_base_run
--     FOREIGN KEY (content_id, base_run_id) REFERENCES content_run (content_id, id);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL 'content_revision is immutable'

CREATE TABLE content_run (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    content_id            BIGINT       NOT NULL,
    content_revision_id   BIGINT       NOT NULL,
    run_no                INT          NOT NULL,
    pipeline_version      VARCHAR(64)  NOT NULL COMMENT '서버 선택(§7.1)',
    execution_mode        VARCHAR(8)   NOT NULL,
    fake_scenario         VARCHAR(48)  NULL,
    status                VARCHAR(16)  NOT NULL,
    cancel_requested_at   DATETIME(6)  NULL,
    cancel_requested_by   BIGINT       NULL,
    cancel_reason         VARCHAR(500) NULL,
    fail_fast_at          DATETIME(6)  NULL COMMENT '첫 FAILED job 시각. 이후 새 claim·승격 중지',
    output_manifest_hash  CHAR(64)     COLLATE utf8mb4_bin NULL COMMENT 'T05 이후 LIVE 성공 run만. T03은 항상 NULL',
    requested_by          BIGINT       NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            DATETIME(6)  NOT NULL,
    started_at            DATETIME(6)  NULL,
    finished_at           DATETIME(6)  NULL,
    CONSTRAINT pk_content_run PRIMARY KEY (id),
    CONSTRAINT uk_content_run_no UNIQUE (content_id, run_no),
    CONSTRAINT uk_content_run_owner UNIQUE (content_id, id),
    CONSTRAINT fk_run_revision FOREIGN KEY (content_id, content_revision_id)
        REFERENCES content_revision (content_id, id),
    CONSTRAINT fk_run_requested_by FOREIGN KEY (requested_by) REFERENCES admin_user (id),
    CONSTRAINT fk_run_cancel_by FOREIGN KEY (cancel_requested_by) REFERENCES admin_user (id),
    CONSTRAINT ck_run_no CHECK (run_no >= 1),
    CONSTRAINT ck_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- T07에서 신규 migration으로 LIVE 허용
    CONSTRAINT ck_run_mode CHECK (execution_mode IN ('FAKE')),
    CONSTRAINT ck_run_fake_scenario CHECK (fake_scenario IS NULL OR execution_mode = 'FAKE'),
    CONSTRAINT ck_run_finished CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_run_started CHECK (status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED') OR started_at IS NOT NULL),
    CONSTRAINT ck_run_queued CHECK (status <> 'QUEUED' OR started_at IS NULL),
    CONSTRAINT ck_run_cancel CHECK ((cancel_requested_at IS NULL) = (cancel_requested_by IS NULL)
        AND (cancel_reason IS NULL OR cancel_requested_at IS NOT NULL)),
    CONSTRAINT ck_run_manifest CHECK (output_manifest_hash IS NULL OR (status = 'SUCCEEDED'
        AND output_manifest_hash REGEXP '(?-i)^[0-9a-f]{64}$')),
    INDEX ix_run_content (content_id, id),
    INDEX ix_run_status (status, id)
);
-- 트리거:
--   BEFORE UPDATE: content_id, content_revision_id, run_no, pipeline_version, execution_mode, fake_scenario,
--                  requested_by, created_at 변경 시 SIGNAL;
--                  OLD.status ∈ {SUCCEEDED, FAILED, CANCELLED}이면 SIGNAL(종료 불변)
--   BEFORE DELETE: SIGNAL

CREATE TABLE automation_job (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    run_id                    BIGINT       NOT NULL,
    content_id                BIGINT       NOT NULL COMMENT '불변 복제(상한·필터·비용 인터페이스)',
    channel_id                BIGINT       NOT NULL COMMENT '불변 복제',
    job_key                   VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    job_type                  VARCHAR(48)  NOT NULL,
    node_kind                 VARCHAR(16)  NOT NULL,
    spawned_by_job_id         BIGINT       NULL COMMENT '확장 자식이면 EXPANDER job(§7.3). T03은 항상 NULL',
    requires_operation        BOOLEAN      NOT NULL COMMENT '외부 부작용(비용) 있는 job. job_operation 필수(§13)',
    status                    VARCHAR(24)  NOT NULL,
    status_reason             VARCHAR(64)  NULL,
    available_at              DATETIME(6)  NOT NULL COMMENT 'READY/RETRY_WAIT claim 가능 시각(DB 시각)',
    attempt_count             INT          NOT NULL DEFAULT 0 COMMENT '모든 claim 수 = 마지막 attempt_no',
    auto_attempts_used        SMALLINT     NOT NULL DEFAULT 0,
    max_auto_attempts         SMALLINT     NOT NULL COMMENT 'pipeline 정의에서 복사, 불변. 기본 3(최초 포함)',
    manual_resumes_used       SMALLINT     NOT NULL DEFAULT 0,
    max_manual_resumes        SMALLINT     NOT NULL COMMENT 'pipeline 정의에서 복사, 불변. 기본 2',
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
    created_at                DATETIME(6)  NOT NULL,
    updated_at                DATETIME(6)  NOT NULL,
    finished_at               DATETIME(6)  NULL,
    CONSTRAINT pk_automation_job PRIMARY KEY (id),
    CONSTRAINT uk_job_key UNIQUE (run_id, job_key),
    CONSTRAINT uk_job_owner UNIQUE (run_id, id),
    CONSTRAINT fk_job_run FOREIGN KEY (content_id, run_id) REFERENCES content_run (content_id, id),
    CONSTRAINT fk_job_content FOREIGN KEY (content_id, channel_id) REFERENCES content (id, channel_id),
    CONSTRAINT fk_job_spawned_by FOREIGN KEY (run_id, spawned_by_job_id) REFERENCES automation_job (run_id, id),
    CONSTRAINT ck_job_status CHECK (status IN ('WAITING', 'READY', 'RUNNING', 'RETRY_WAIT', 'ACTION_REQUIRED',
        'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_job_key CHECK (job_key REGEXP '^[a-z0-9][a-z0-9:/._-]{0,127}$'),
    CONSTRAINT ck_job_type CHECK (job_type REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,47}$'),
    CONSTRAINT ck_job_node CHECK (node_kind IN ('TASK', 'EXPANDER', 'BARRIER')),
    CONSTRAINT ck_job_child CHECK (spawned_by_job_id IS NULL OR node_kind = 'TASK'),
    CONSTRAINT ck_job_reason CHECK (status_reason IS NULL OR status_reason REGEXP '(?-i)^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_job_lease CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL)
        AND (lease_token IS NULL) = (lease_owner IS NULL)
        AND (lease_token IS NULL) = (lease_expires_at IS NULL)
        AND (lease_token IS NULL OR current_attempt_no IS NOT NULL)),
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
    CONSTRAINT ck_job_timeout CHECK (execution_timeout_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_job_hash CHECK (input_hash IS NULL OR input_hash REGEXP '(?-i)^[0-9a-f]{64}$'),
    INDEX ix_job_claim (status, available_at, id),
    INDEX ix_job_lease (status, lease_expires_at, id),
    INDEX ix_job_run (run_id, status, id),
    INDEX ix_job_channel (channel_id, status, id),
    INDEX ix_job_reuse (job_type, input_hash)
);
-- job_attempt 생성 후 추가:
-- ALTER TABLE automation_job ADD CONSTRAINT fk_job_current_attempt
--     FOREIGN KEY (id, current_attempt_no) REFERENCES job_attempt (job_id, attempt_no);
-- 트리거:
--   BEFORE UPDATE: run_id, content_id, channel_id, job_key, job_type, node_kind, spawned_by_job_id,
--                  requires_operation, max_auto_attempts, max_manual_resumes, execution_timeout_seconds,
--                  input_hash, created_at 변경 시 SIGNAL;
--                  OLD.status가 종료 상태이면 SIGNAL;
--                  OLD.result_summary_json IS NOT NULL AND NEW.result_summary_json <=> OLD가 아니면 SIGNAL
--   BEFORE DELETE: SIGNAL

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
-- 트리거:
--   BEFORE INSERT: (SELECT status FROM automation_job WHERE id = NEW.job_id) <> 'WAITING'이면 SIGNAL
--                  ← 이미 승격·실행된 노드로 들어가는 간선 추가 금지. 확장 계약의 DB 방어선(§7.3)
--   BEFORE UPDATE / BEFORE DELETE: SIGNAL

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
    CONSTRAINT ck_attempt_deadline CHECK (execution_deadline_at > started_at),
    CONSTRAINT ck_attempt_outcome CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED', 'RETRYABLE_ERROR',
        'ACTION_REQUIRED', 'FATAL_ERROR', 'OUTCOME_UNKNOWN', 'LEASE_EXPIRED', 'DEADLINE_EXCEEDED',
        'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT ck_attempt_finished CHECK ((finished_at IS NULL) = (outcome IS NULL))
);
-- 트리거:
--   BEFORE UPDATE: OLD.finished_at IS NOT NULL이면 SIGNAL;
--                  job_id, attempt_no, trigger_kind, worker_instance_id, lease_owner, lease_token,
--                  started_at, execution_deadline_at 변경 시 SIGNAL
--   BEFORE DELETE: SIGNAL

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
    CONSTRAINT ck_event_attempt CHECK (attempt_id IS NULL OR job_id IS NOT NULL),
    INDEX ix_event_run (run_id, id),
    INDEX ix_event_job (job_id, id)
);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL 'job_event is append-only'

CREATE TABLE queue_control (
    id                  TINYINT      NOT NULL,
    dispatch_enabled    BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '새 claim 허용. 지출 허용(budget_control)과 독립',
    max_running_jobs    SMALLINT     NOT NULL DEFAULT 2 COMMENT 'DB 전역 RUNNING 상한(§6.6)',
    dispatch_changed_at DATETIME(6)  NULL,
    dispatch_changed_by BIGINT       NULL,
    dispatch_reason     VARCHAR(500) NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_queue_control PRIMARY KEY (id),
    CONSTRAINT ck_queue_control_singleton CHECK (id = 1),
    -- 2 초과는 ADR 필요
    CONSTRAINT ck_queue_control_max CHECK (max_running_jobs BETWEEN 1 AND 2),
    CONSTRAINT fk_queue_control_by FOREIGN KEY (dispatch_changed_by) REFERENCES admin_user (id)
);
INSERT INTO queue_control (id, dispatch_enabled, max_running_jobs, version, updated_at)
VALUES (1, TRUE, 2, 0, UTC_TIMESTAMP(6));
-- 트리거: BEFORE DELETE → SIGNAL

CREATE TABLE queue_worker_instance (
    instance_id    CHAR(36)     COLLATE utf8mb4_bin NOT NULL COMMENT '프로세스 기동마다 UUID',
    host_label     VARCHAR(100) NOT NULL COMMENT '호스트명 등 진단용(비밀 아님)',
    worker_threads SMALLINT     NOT NULL,
    started_at     DATETIME(6)  NOT NULL,
    last_seen_at   DATETIME(6)  NOT NULL,
    stopped_at     DATETIME(6)  NULL,
    CONSTRAINT pk_queue_worker_instance PRIMARY KEY (instance_id),
    CONSTRAINT ck_worker_threads CHECK (worker_threads BETWEEN 0 AND 8),
    INDEX ix_worker_seen (stopped_at, last_seen_at)
);
-- 트리거: BEFORE DELETE → SIGNAL (보존 정책은 별도)

CREATE TABLE queue_command_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    request_key         VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Idempotency-Key 헤더 값',
    request_fingerprint CHAR(64)     COLLATE utf8mb4_bin NOT NULL COMMENT 'command_type + 경로 ID + 정규화 본문',
    command_type        VARCHAR(32)  NOT NULL,
    target_type         VARCHAR(32)  NOT NULL,
    target_id           BIGINT       NOT NULL,
    actor_user_id       BIGINT       NOT NULL,
    trace_id            VARCHAR(64)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_queue_command_log PRIMARY KEY (id),
    CONSTRAINT uk_queue_command_key UNIQUE (request_key),
    CONSTRAINT fk_queue_command_actor FOREIGN KEY (actor_user_id) REFERENCES admin_user (id),
    CONSTRAINT ck_queue_command_key CHECK (request_key REGEXP '^[A-Za-z0-9:._-]{8,128}$'),
    CONSTRAINT ck_queue_command_fp CHECK (request_fingerprint REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_queue_command_type CHECK (command_type IN ('CREATE_TOPIC', 'CREATE_CONTENT', 'CREATE_REVISION',
        'CREATE_RUN', 'CANCEL_RUN', 'RESUME_JOB')),
    INDEX ix_queue_command_target (target_type, target_id)
);
-- 트리거: BEFORE UPDATE / BEFORE DELETE → SIGNAL

-- T03-A6 review draft. Do not execute or install in Flyway.
-- Domain/queue definitions above derive from A3. V1–V5 remain unchanged.
ALTER TABLE content ADD UNIQUE (id, channel_id, content_type);
ALTER TABLE content ADD FOREIGN KEY (id,current_revision_id) REFERENCES content_revision(content_id,id),
 ADD FOREIGN KEY (id,active_run_id) REFERENCES content_run(content_id,id);
ALTER TABLE content_revision ADD FOREIGN KEY (content_id,base_run_id) REFERENCES content_run(content_id,id);
ALTER TABLE automation_job ADD FOREIGN KEY (id,current_attempt_no) REFERENCES job_attempt(job_id,attempt_no);
ALTER TABLE budget_bucket DROP CONSTRAINT ck_budget_bucket_no_content,
 ADD content_channel_id BIGINT NULL,
 ADD content_type VARCHAR(16) NULL,
 ADD UNIQUE (policy_id,id),
 ADD UNIQUE (policy_id,content_id),
 ADD FOREIGN KEY (content_id,content_channel_id,content_type) REFERENCES content(id,channel_id,content_type),
 ADD CHECK (
   (content_id IS NULL AND content_channel_id IS NULL AND content_type IS NULL)
   OR (content_id IS NOT NULL AND content_channel_id IS NOT NULL AND content_type IS NOT NULL
   AND period_start='2000-01-01 00:00:00.000000' AND period_end='9999-12-31 23:59:59.999999' AND timezone='UTC'));
ALTER TABLE budget_reservation DROP CONSTRAINT ck_budget_reservation_no_content,
 ADD link_contract_version TINYINT NOT NULL DEFAULT 0,
 ADD content_type VARCHAR(16) NULL,
 ADD UNIQUE (id,request_key),
 ADD FOREIGN KEY (content_id,channel_id,content_type) REFERENCES content(id,channel_id,content_type),
 ADD CHECK ((link_contract_version=0 AND content_id IS NULL AND content_type IS NULL)
   OR (link_contract_version=1 AND content_id IS NOT NULL AND channel_id IS NOT NULL AND content_type IS NOT NULL));
CREATE TABLE content_budget_policy (
 content_id BIGINT NOT NULL, channel_id BIGINT NOT NULL, content_type VARCHAR(16) NOT NULL,
 policy_id BIGINT NOT NULL, first_applied_at DATETIME(6) NOT NULL,
 PRIMARY KEY (content_id,policy_id), INDEX (content_type,channel_id,content_id),
 FOREIGN KEY (content_id,channel_id,content_type) REFERENCES content(id,channel_id,content_type),
 FOREIGN KEY (policy_id) REFERENCES budget_policy(id)
);
ALTER TABLE budget_control ADD policy_generation BIGINT NOT NULL DEFAULT 0,
 ADD check_generation BIGINT NOT NULL DEFAULT 0,
 ADD check_phase VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 ADD CHECK (check_phase IN ('UNKNOWN','RUNNING','FAILED','COMPLETE'));
CREATE TABLE budget_policy_state_event (
 policy_generation BIGINT NOT NULL, policy_id BIGINT NOT NULL, revision_no INT NOT NULL,
 status VARCHAR(16) NOT NULL, changed_at DATETIME(6) NOT NULL,
 PRIMARY KEY (policy_generation,policy_id), INDEX (policy_id,policy_generation),
 FOREIGN KEY (policy_id,revision_no) REFERENCES budget_policy_revision(policy_id,revision_no),
 CHECK (status IN ('ACTIVE','DISABLED'))
);
-- Initialize generation 0 from each policy's current revision/status at cutover. v0 history is untouched.
CREATE TABLE reservation_policy_selection (
 reservation_id BIGINT PRIMARY KEY, policy_generation BIGINT NOT NULL,
 decided_at DATETIME(6) NOT NULL, selected_count INT NOT NULL CHECK (selected_count>=3),
 selected_fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 FOREIGN KEY (reservation_id) REFERENCES budget_reservation(id)
);
CREATE TABLE reservation_expected_bucket (
 reservation_id BIGINT NOT NULL, policy_id BIGINT NOT NULL, bucket_id BIGINT NOT NULL,
 policy_revision_no INT NOT NULL, applied_limit_krw DECIMAL(24,6) NOT NULL,
 period_start DATETIME(6) NOT NULL, period_end DATETIME(6) NOT NULL, decided_at DATETIME(6) NOT NULL,
 PRIMARY KEY (reservation_id,bucket_id), UNIQUE (reservation_id,policy_id),
 FOREIGN KEY (reservation_id) REFERENCES reservation_policy_selection(reservation_id),
 FOREIGN KEY (policy_id,bucket_id) REFERENCES budget_bucket(policy_id,id),
 FOREIGN KEY (policy_id,policy_revision_no) REFERENCES budget_policy_revision(policy_id,revision_no),
 CHECK (period_start<=decided_at AND decided_at<period_end AND applied_limit_krw>=0)
);
CREATE TABLE job_operation (
 operation_id CHAR(36) COLLATE utf8mb4_bin PRIMARY KEY, job_id BIGINT NOT NULL,
 effect_slot VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, operation_seq INT NOT NULL,
 created_attempt_id BIGINT NOT NULL, payload_version INT NOT NULL, payload JSON NOT NULL,
 fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 reservation_request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, reservation_id BIGINT NULL,
 state VARCHAR(24) NOT NULL, version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL, cancel_intent_at DATETIME(6) NULL, queue_applied_at DATETIME(6) NULL,
 UNIQUE (job_id,effect_slot), UNIQUE (job_id,operation_seq), UNIQUE (operation_id,job_id),
 UNIQUE (reservation_request_key), UNIQUE (operation_id,reservation_request_key), UNIQUE (reservation_id),
 FOREIGN KEY (job_id,created_attempt_id) REFERENCES job_attempt(job_id,id),
 FOREIGN KEY (reservation_id,reservation_request_key) REFERENCES budget_reservation(id,request_key),
 INDEX (state,updated_at,operation_id),
 CHECK (state IN ('PREPARED','RESERVED','DISPATCHING','RECOVERY_REQUIRED','SETTLEMENT_PENDING','TERMINAL'))
);
CREATE TABLE reservation_intent (
 request_key VARCHAR(128) COLLATE utf8mb4_bin PRIMARY KEY,
 operation_id CHAR(36) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL, phase VARCHAR(20) NOT NULL,
 reservation_id BIGINT NULL UNIQUE, updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (operation_id,request_key) REFERENCES job_operation(operation_id,reservation_request_key),
 FOREIGN KEY (reservation_id,request_key) REFERENCES budget_reservation(id,request_key),
 INDEX (phase,updated_at),
 CHECK ((phase IN ('OPEN','VOID') AND reservation_id IS NULL) OR
  (phase IN ('RESERVED','CLOSE_REQUESTED','RELEASED') AND reservation_id IS NOT NULL))
);
CREATE TABLE operation_dispatch (
 dispatch_id CHAR(36) COLLATE utf8mb4_bin PRIMARY KEY, operation_id CHAR(36) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 job_id BIGINT NOT NULL, attempt_id BIGINT NOT NULL, lease_token CHAR(36) COLLATE utf8mb4_bin NOT NULL,
 fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL, approved_at DATETIME(6) NOT NULL,
 FOREIGN KEY (operation_id,job_id) REFERENCES job_operation(operation_id,job_id),
 FOREIGN KEY (job_id,attempt_id) REFERENCES job_attempt(job_id,id)
);
CREATE TABLE operation_evidence (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, dispatch_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
 source VARCHAR(24) COLLATE utf8mb4_bin NOT NULL, digest CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 artifact_ref VARCHAR(500) NULL, artifact_hash CHAR(64) COLLATE utf8mb4_bin NULL,
 billing_outcome VARCHAR(16) NOT NULL, artifact_outcome VARCHAR(16) NOT NULL, metadata_json JSON NOT NULL,
 received_at DATETIME(6) NOT NULL, UNIQUE (dispatch_id,source,digest),
 FOREIGN KEY (dispatch_id) REFERENCES operation_dispatch(dispatch_id),
 CHECK (billing_outcome IN ('CHARGED','NOT_CHARGED','UNKNOWN')),
 CHECK (artifact_outcome IN ('VERIFIED','FAILED','UNKNOWN'))
);
CREATE TABLE operation_event_intent (
 operation_id CHAR(36) COLLATE utf8mb4_bin NOT NULL, event_seq INT NOT NULL,
 event_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE, entry_type VARCHAR(16) NOT NULL,
 payload JSON NOT NULL, fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 state VARCHAR(16) NOT NULL, cost_log_id BIGINT NULL UNIQUE, receipt JSON NULL, updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (operation_id,event_seq), FOREIGN KEY (operation_id) REFERENCES job_operation(operation_id),
 FOREIGN KEY (cost_log_id) REFERENCES cost_log(id), INDEX (state,updated_at),
 CHECK (event_seq>=1), CHECK (entry_type IN ('RELEASE','PROVISIONAL','CONFIRM','ADJUSTMENT','REFUND')),
 CHECK ((state='PENDING' AND receipt IS NULL AND cost_log_id IS NULL) OR
        (state='COMMITTED' AND receipt IS NOT NULL AND cost_log_id IS NOT NULL))
);
CREATE TABLE operation_receipt (
 operation_id CHAR(36) COLLATE utf8mb4_bin PRIMARY KEY, terminal_event_seq INT NULL,
 outcome VARCHAR(24) NOT NULL, receipt JSON NOT NULL, committed_at DATETIME(6) NOT NULL,
 FOREIGN KEY (operation_id) REFERENCES job_operation(operation_id),
 FOREIGN KEY (operation_id,terminal_event_seq) REFERENCES operation_event_intent(operation_id,event_seq),
 CHECK (outcome IN ('EXECUTED_RESULT_ADOPTED','NOT_EXECUTED','EXECUTED_FAILED'))
);
CREATE TABLE settlement_operation (
 operation_id CHAR(36) COLLATE utf8mb4_bin PRIMARY KEY, reservation_id BIGINT NOT NULL,
 creation_request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 event_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE, fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 expected_reservation_version BIGINT NOT NULL, prior_operation_id CHAR(36) COLLATE utf8mb4_bin NULL,
 payload JSON NOT NULL, requested_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
 state VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0, cost_log_id BIGINT NULL UNIQUE,
 receipt JSON NULL, finished_at DATETIME(6) NULL,
 UNIQUE (reservation_id,operation_id), INDEX (reservation_id,state,created_at),
 FOREIGN KEY (reservation_id) REFERENCES budget_reservation(id),
 FOREIGN KEY (reservation_id,prior_operation_id) REFERENCES settlement_operation(reservation_id,operation_id),
 FOREIGN KEY (requested_by) REFERENCES admin_user(id), FOREIGN KEY (cost_log_id) REFERENCES cost_log(id),
 CHECK ((state='PENDING' AND finished_at IS NULL AND receipt IS NULL AND cost_log_id IS NULL)
  OR (state='COMMITTED' AND finished_at IS NOT NULL AND receipt IS NOT NULL AND cost_log_id IS NOT NULL)
  OR (state IN ('REJECTED','CANCELLED') AND finished_at IS NOT NULL AND receipt IS NOT NULL AND cost_log_id IS NULL))
);
CREATE TABLE settlement_guard (
 reservation_id BIGINT PRIMARY KEY, operation_id CHAR(36) COLLATE utf8mb4_bin NULL UNIQUE,
 version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (reservation_id) REFERENCES budget_reservation(id),
 FOREIGN KEY (reservation_id,operation_id) REFERENCES settlement_operation(reservation_id,operation_id)
);
CREATE TABLE operation_attestation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_id CHAR(36) COLLATE utf8mb4_bin NOT NULL,
 request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE, fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 actor_user_id BIGINT NOT NULL, evidence_ref VARCHAR(500) NOT NULL, reason VARCHAR(500) NOT NULL,
 decided_at DATETIME(6) NOT NULL, previous_version BIGINT NOT NULL,
 billing_outcome VARCHAR(16) NOT NULL, artifact_outcome VARCHAR(16) NOT NULL,
 FOREIGN KEY (actor_user_id) REFERENCES admin_user(id), FOREIGN KEY (operation_id) REFERENCES job_operation(operation_id),
 CHECK (billing_outcome IN ('CHARGED','NOT_CHARGED','UNKNOWN')),
 CHECK (artifact_outcome IN ('VERIFIED','FAILED','UNKNOWN'))
);
CREATE TABLE consistency_check_run (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, generation BIGINT NOT NULL UNIQUE, status VARCHAR(16) NOT NULL,
 check_version INT NOT NULL, started_at DATETIME(6) NOT NULL, finished_at DATETIME(6) NULL,
 issue_count BIGINT NOT NULL DEFAULT 0, CHECK (status IN ('RUNNING','FAILED','OK','ERROR'))
);
CREATE TABLE consistency_issue (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, check_id BIGINT NOT NULL, issue_code VARCHAR(64) NOT NULL,
 entity_type VARCHAR(32) NOT NULL, entity_id BIGINT NOT NULL, difference_json JSON NOT NULL,
 FOREIGN KEY (check_id) REFERENCES consistency_check_run(id), INDEX (check_id,id)
);
-- Review prerequisites before Flyway authoring: operational privilege table, trigger scripts for
-- immutable snapshots/receipts and terminal rows, singleton seed, cutover generation baseline,
-- exact Flyway file ordering, and MariaDB FK/UNIQUE lock validation.

