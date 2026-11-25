-- V6__create_content_domain (T03-B, FD-01/13/16, ADR-067~076). 설계: docs/plans/t03-final/T03_FINAL_DATABASE.md,
-- T03_SCHEMA_DRAFT.sql(도메인 범위). 적용 후 수정 금지(ADR-009). 큐 실행·비용 테이블은 V7 이후(C/D) 범위다.
-- CHARSET/COLLATE 생략으로 DB 기본 utf8mb4_unicode_ci 상속. utf8mb4_bin은 정확 비교 컬럼만(ADR-053).
-- 순환 FK(content ↔ content_revision ↔ content_run)는 부모·자식 테이블 생성 뒤 ALTER로 추가한다.

-- 1) revision → channel_config 소유권 복합 FK 대상 (V4는 (channel_id, revision_no)만 UNIQUE)
ALTER TABLE channel_config ADD CONSTRAINT uk_channel_config_owner UNIQUE (channel_id, id);

-- 2) topic: 채널 안의 제작 소재. 불변(수정 = 새 topic). 같은 채널 정규화 제목 중복 금지
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

-- 3) content: 채널·유형·topic에 고정된 제작 단위. 포인터·상태·제목 복제본·version만 변경
CREATE TABLE content (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    channel_id          BIGINT       NOT NULL,
    topic_id            BIGINT       NOT NULL,
    content_type        VARCHAR(16)  NOT NULL,
    title               VARCHAR(255) NOT NULL COMMENT '현재 revision working_title 복제본',
    status              VARCHAR(16)  NOT NULL,
    current_revision_id BIGINT       NULL COMMENT '생성 TX에서만 NULL, 이후 NULL로 되돌릴 수 없음(트리거)',
    active_run_id       BIGINT       NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_by          BIGINT       NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    archived_at         DATETIME(6)  NULL,
    CONSTRAINT pk_content PRIMARY KEY (id),
    CONSTRAINT uk_content_channel UNIQUE (id, channel_id),
    CONSTRAINT uk_content_type UNIQUE (id, content_type),
    CONSTRAINT uk_content_channel_type UNIQUE (id, channel_id, content_type),
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

-- 4) content_revision: 불변 입력 사양. channel_id는 content.channel_id 복제(복합 FK용, 불변)
CREATE TABLE content_revision (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    content_id              BIGINT       NOT NULL,
    channel_id              BIGINT       NOT NULL COMMENT 'content.channel_id 복제(복합 FK용, 불변)',
    revision_no             INT          NOT NULL,
    channel_config_id       BIGINT       NOT NULL COMMENT '이 revision이 고정한 채널 설정 revision',
    base_run_id             BIGINT       NULL COMMENT '편집 기준 run(같은 콘텐츠, SUCCEEDED). 재사용은 T05',
    working_title           VARCHAR(255) NOT NULL,
    brief                   TEXT         NOT NULL COMMENT '제작 지시문',
    language                VARCHAR(16)  NOT NULL,
    target_duration_seconds INT          NOT NULL,
    settings_json           JSON         NULL COMMENT '비핵심 옵션만(ADR-015). 앱에서 16KB 제한',
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

-- 5) content_run: revision을 고정 pipeline으로 한 번 실행한 시도. T03은 FAKE 전용(FD-13), 실행 연결은 T03-C
CREATE TABLE content_run (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    content_id            BIGINT       NOT NULL,
    content_revision_id   BIGINT       NOT NULL,
    run_no                INT          NOT NULL,
    pipeline_version      VARCHAR(64)  NOT NULL COMMENT '서버 선택',
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
    -- FAKE run은 manifest를 만들지 않으며 승인 대상이 될 수 없다
    CONSTRAINT ck_run_fake_manifest CHECK (execution_mode <> 'FAKE' OR output_manifest_hash IS NULL),
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

-- 6~8) 역참조 소유권 FK: 포인터는 같은 콘텐츠의 revision/run만 가리킨다
ALTER TABLE content
    ADD CONSTRAINT fk_content_current_revision FOREIGN KEY (id, current_revision_id)
        REFERENCES content_revision (content_id, id),
    ADD CONSTRAINT fk_content_active_run FOREIGN KEY (id, active_run_id)
        REFERENCES content_run (content_id, id);

ALTER TABLE content_revision
    ADD CONSTRAINT fk_revision_base_run FOREIGN KEY (content_id, base_run_id)
        REFERENCES content_run (content_id, id);

-- 9) queue_command_log: Topic/Content/Revision(이후 Run·Job 명령) 작성 멱등 키. 명령 효과와 같은 TX에서 INSERT
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

-- 10) 불변·상태 보호 트리거 (FV-02). 운영 앱 계정 권한(prod-app-grants.sql)과 이중 방어
CREATE TRIGGER trg_topic_no_update BEFORE UPDATE ON topic FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'topic is immutable';
CREATE TRIGGER trg_topic_no_delete BEFORE DELETE ON topic FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'topic is immutable';

CREATE TRIGGER trg_content_revision_no_update BEFORE UPDATE ON content_revision FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_revision is immutable';
CREATE TRIGGER trg_content_revision_no_delete BEFORE DELETE ON content_revision FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_revision is immutable';

CREATE TRIGGER trg_queue_command_log_no_update BEFORE UPDATE ON queue_command_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'queue_command_log is append-only';
CREATE TRIGGER trg_queue_command_log_no_delete BEFORE DELETE ON queue_command_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'queue_command_log is append-only';

CREATE TRIGGER trg_content_no_delete BEFORE DELETE ON content FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content cannot be deleted';
CREATE TRIGGER trg_content_run_no_delete BEFORE DELETE ON content_run FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_run cannot be deleted';

DELIMITER $$

-- 기준 run은 같은 콘텐츠의 SUCCEEDED run이어야 한다(복합 FK가 소유권, 트리거가 상태를 확인)
CREATE TRIGGER trg_content_revision_before_insert BEFORE INSERT ON content_revision FOR EACH ROW
BEGIN
    IF NEW.base_run_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM content_run r
             WHERE r.id = NEW.base_run_id AND r.content_id = NEW.content_id AND r.status = 'SUCCEEDED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'base run must be a succeeded run of the same content';
    END IF;
END$$

-- 새 content는 DRAFT, 포인터 NULL로 시작하고 같은 TX에서 최초 revision 포인터를 설정한다
CREATE TRIGGER trg_content_before_insert BEFORE INSERT ON content FOR EACH ROW
BEGIN
    IF NEW.status <> 'DRAFT' OR NEW.current_revision_id IS NOT NULL OR NEW.active_run_id IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content must be created as DRAFT without pointers';
    END IF;
END$$

CREATE TRIGGER trg_content_before_update BEFORE UPDATE ON content FOR EACH ROW
BEGIN
    IF OLD.status = 'ARCHIVED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'archived content is immutable';
    END IF;
    IF NOT (NEW.id <=> OLD.id AND NEW.channel_id <=> OLD.channel_id AND NEW.topic_id <=> OLD.topic_id
            AND BINARY NEW.content_type <=> BINARY OLD.content_type AND NEW.created_by <=> OLD.created_by
            AND NEW.created_at <=> OLD.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content owner columns are immutable';
    END IF;
    -- NULL 포인터로 현재 revision 소유권 검사를 우회하지 못한다
    IF OLD.current_revision_id IS NOT NULL AND NEW.current_revision_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'current revision cannot be cleared';
    END IF;
    -- 활성 run이 있는 동안 현재 revision을 바꾸지 않는다(revision 생성은 DRAFT에서만)
    IF OLD.active_run_id IS NOT NULL AND NOT (NEW.current_revision_id <=> OLD.current_revision_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'current revision cannot change while a run is active';
    END IF;
    -- 제목은 현재 revision working_title의 정확한 복제본
    IF NEW.current_revision_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM content_revision r
             WHERE r.id = NEW.current_revision_id AND r.content_id = NEW.id
               AND BINARY r.working_title = BINARY NEW.title) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content title must match the current revision';
    END IF;
    -- 활성 포인터는 같은 콘텐츠의 비종료 run만 새로 가리킬 수 있다
    IF NEW.active_run_id IS NOT NULL AND NOT (NEW.active_run_id <=> OLD.active_run_id) AND NOT EXISTS (
            SELECT 1 FROM content_run r
             WHERE r.id = NEW.active_run_id AND r.content_id = NEW.id AND r.status IN ('QUEUED', 'RUNNING')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'active run must be a non-terminal run of the content';
    END IF;
END$$

CREATE TRIGGER trg_content_run_before_insert BEFORE INSERT ON content_run FOR EACH ROW
BEGIN
    IF NEW.status <> 'QUEUED' OR NEW.cancel_requested_at IS NOT NULL OR NEW.fail_fast_at IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_run must be created as QUEUED';
    END IF;
END$$

CREATE TRIGGER trg_content_run_before_update BEFORE UPDATE ON content_run FOR EACH ROW
BEGIN
    IF OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'terminal content_run is immutable';
    END IF;
    IF NOT (NEW.id <=> OLD.id AND NEW.content_id <=> OLD.content_id
            AND NEW.content_revision_id <=> OLD.content_revision_id AND NEW.run_no <=> OLD.run_no
            AND BINARY NEW.pipeline_version <=> BINARY OLD.pipeline_version
            AND BINARY NEW.execution_mode <=> BINARY OLD.execution_mode
            AND BINARY NEW.fake_scenario <=> BINARY OLD.fake_scenario
            AND NEW.requested_by <=> OLD.requested_by AND NEW.created_at <=> OLD.created_at) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_run identity columns are immutable';
    END IF;
    IF NEW.status = 'QUEUED' AND OLD.status <> 'QUEUED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_run cannot return to QUEUED';
    END IF;
    -- 취소 의도는 한 번 기록되면 바뀌지 않는다
    IF OLD.cancel_requested_at IS NOT NULL AND NOT (NEW.cancel_requested_at <=> OLD.cancel_requested_at
            AND NEW.cancel_requested_by <=> OLD.cancel_requested_by
            AND BINARY NEW.cancel_reason <=> BINARY OLD.cancel_reason) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'content_run cancel intent is immutable';
    END IF;
    -- R10: 활성 포인터 해제(content 갱신)를 같은 TX에서 먼저 한 뒤 run을 종료한다
    IF NEW.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND EXISTS (
            SELECT 1 FROM content c WHERE c.id = NEW.content_id AND c.active_run_id = NEW.id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'release the active run pointer before finishing the run';
    END IF;
END$$

DELIMITER ;
