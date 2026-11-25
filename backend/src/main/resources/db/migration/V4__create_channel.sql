-- V4__create_channel (T02, ADR-026~061). 설계: docs/design/T02_DATA_MODEL.md. 적용 후 수정 금지(ADR-009).
-- CHARSET/COLLATE 생략으로 DB 기본 utf8mb4_unicode_ci 상속. utf8mb4_bin은 정확 비교 컬럼만(ADR-053).

CREATE TABLE channel (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    language    VARCHAR(16)  NOT NULL COMMENT 'BCP 47 (예: en, en-US)',
    timezone    VARCHAR(64)  NOT NULL COMMENT 'IANA 시간대, 화면 표시용. 앱에서 ZoneId 검증',
    status      VARCHAR(16)  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT 'optimistic lock(설정 revision_no와 별개, ADR-034)',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    archived_at DATETIME(6)  NULL,
    CONSTRAINT pk_channel PRIMARY KEY (id),
    CONSTRAINT uk_channel_name UNIQUE (name),
    CONSTRAINT ck_channel_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_channel_language CHECK (language REGEXP '(?-i)^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_channel_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT ck_channel_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL)),
    INDEX ix_channel_status (status, id)
);

-- 채널 설정: 불변 revision(ADR-034, ADR-059). 현재 설정 = channel_id별 최대 revision_no. Provider 옵션만 JSON.
CREATE TABLE channel_config (
    id                           BIGINT        NOT NULL AUTO_INCREMENT,
    channel_id                   BIGINT        NOT NULL,
    revision_no                  INT           NOT NULL,
    default_content_type         VARCHAR(16)   NOT NULL COMMENT '제작 시 기본 선택값(ADR-039)',
    shorts_min_seconds           SMALLINT      NOT NULL,
    shorts_max_seconds           SMALLINT      NOT NULL,
    longform_min_seconds         INT           NOT NULL,
    longform_max_seconds         INT           NOT NULL,
    content_strategy             VARCHAR(2000) NULL,
    target_audience              VARCHAR(500)  NULL,
    narration_style              VARCHAR(500)  NULL,
    visual_style                 VARCHAR(500)  NULL,
    synthetic_disclosure_default BOOLEAN       NOT NULL COMMENT 'UI 초기값일 뿐, 영상별 고지 판단을 대체하지 않음(ADR-039)',
    provider_options_json        JSON          NULL COMMENT '비핵심 provider 옵션. 비밀값 저장 금지',
    change_note                  VARCHAR(500)  NULL,
    created_by                   BIGINT        NOT NULL,
    created_at                   DATETIME(6)   NOT NULL,
    CONSTRAINT pk_channel_config PRIMARY KEY (id),
    CONSTRAINT uk_channel_config_revision UNIQUE (channel_id, revision_no),
    CONSTRAINT fk_channel_config_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT fk_channel_config_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_channel_config_revision CHECK (revision_no >= 1),
    CONSTRAINT ck_channel_config_type CHECK (default_content_type IN ('SHORTS', 'LONGFORM')),
    CONSTRAINT ck_channel_config_shorts CHECK (shorts_min_seconds >= 1 AND shorts_max_seconds <= 180
        AND shorts_min_seconds <= shorts_max_seconds),
    CONSTRAINT ck_channel_config_longform CHECK (longform_min_seconds >= 60 AND longform_max_seconds <= 14400
        AND longform_min_seconds <= longform_max_seconds)
);

CREATE TRIGGER trg_channel_config_no_update BEFORE UPDATE ON channel_config FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'channel_config revisions are immutable';
CREATE TRIGGER trg_channel_config_no_delete BEFORE DELETE ON channel_config FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'channel_config revisions are immutable';
