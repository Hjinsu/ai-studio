-- V5__create_budget_and_ledger (T02, ADR-026~061). 설계: docs/design/T02_DATA_MODEL.md. 적용 후 수정 금지(ADR-009).
-- CHARSET/COLLATE 생략으로 DB 기본 utf8mb4_unicode_ci 상속. utf8mb4_bin은 정확 비교 컬럼만(ADR-053).

-- 예산 전역 제어 단일 행(ADR-055, ADR-061). 지출 기본 차단. 잠금 순서의 첫 번째 자원.
CREATE TABLE budget_control (
    id                      TINYINT      NOT NULL,
    spending_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    spending_changed_at     DATETIME(6)  NULL,
    spending_changed_by     BIGINT       NULL,
    spending_change_reason  VARCHAR(500) NULL,
    consistency_status      VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ERROR면 신규 예약 차단(ADR-054)',
    consistency_checked_at  DATETIME(6)  NULL,
    consistency_issue_count INT          NOT NULL DEFAULT 0,
    version                 BIGINT       NOT NULL DEFAULT 0,
    updated_at              DATETIME(6)  NOT NULL,
    CONSTRAINT pk_budget_control PRIMARY KEY (id),
    CONSTRAINT ck_budget_control_singleton CHECK (id = 1),
    CONSTRAINT ck_budget_control_consistency CHECK (consistency_status IN ('UNKNOWN', 'OK', 'ERROR')),
    CONSTRAINT ck_budget_control_issue_count CHECK (consistency_issue_count >= 0),
    CONSTRAINT fk_budget_control_changed_by FOREIGN KEY (spending_changed_by) REFERENCES admin_user (id)
);

INSERT INTO budget_control (id, spending_enabled, consistency_status, consistency_issue_count, version, updated_at)
VALUES (1, FALSE, 'UNKNOWN', 0, 0, UTC_TIMESTAMP(6));

CREATE TRIGGER trg_budget_control_no_delete BEFORE DELETE ON budget_control FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'budget_control row cannot be deleted';

-- 환율: 관리자 수동 등록, 불변(ADR-027, ADR-046). quote는 KRW 고정. KRW 자체는 행 없이 환율 1.
CREATE TABLE exchange_rate (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    base_currency  CHAR(3)        NOT NULL,
    quote_currency CHAR(3)        NOT NULL,
    rate           DECIMAL(24,10) NOT NULL COMMENT '1 base = rate KRW',
    effective_from DATETIME(6)    NOT NULL,
    valid_until    DATETIME(6)    NOT NULL COMMENT 'effective_from + 유효기간(기본 72h)',
    source         VARCHAR(32)    NOT NULL,
    note           VARCHAR(500)   NULL,
    created_by     BIGINT         NOT NULL,
    created_at     DATETIME(6)    NOT NULL,
    CONSTRAINT pk_exchange_rate PRIMARY KEY (id),
    CONSTRAINT uk_exchange_rate UNIQUE (base_currency, quote_currency, effective_from),
    CONSTRAINT fk_exchange_rate_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_exchange_rate_base CHECK (base_currency REGEXP '(?-i)^[A-Z]{3}$' AND base_currency <> 'KRW'),
    CONSTRAINT ck_exchange_rate_quote CHECK (quote_currency REGEXP '(?-i)^KRW$'),
    CONSTRAINT ck_exchange_rate_rate CHECK (rate > 0),
    CONSTRAINT ck_exchange_rate_validity CHECK (valid_until > effective_from),
    CONSTRAINT ck_exchange_rate_source CHECK (source IN ('MANUAL'))
);

CREATE TRIGGER trg_exchange_rate_no_update BEFORE UPDATE ON exchange_rate FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'exchange_rate is immutable';
CREATE TRIGGER trg_exchange_rate_no_delete BEFORE DELETE ON exchange_rate FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'exchange_rate is immutable';

-- 예산 정책 헤더. 한도·시간대는 revision(ADR-026). T02는 CONTENT scope 불가(ADR-029, T03에서 확장).
CREATE TABLE budget_policy (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    scope               VARCHAR(16)  NOT NULL,
    channel_id          BIGINT       NULL,
    content_type        VARCHAR(16)  NULL,
    content_id          BIGINT       NULL COMMENT 'T03에서 content FK 추가',
    period_type         VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    current_revision_no INT          NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    scope_key           VARCHAR(100) AS (CONCAT_WS(':', scope, IFNULL(channel_id, '-'), IFNULL(content_type, '-'),
                                         IFNULL(content_id, '-'))) PERSISTENT,
    CONSTRAINT pk_budget_policy PRIMARY KEY (id),
    CONSTRAINT uk_budget_policy_scope UNIQUE (scope_key, period_type),
    CONSTRAINT fk_budget_policy_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT ck_budget_policy_scope CHECK (
        (scope = 'SYSTEM' AND channel_id IS NULL AND content_type IS NULL AND period_type IN ('DAY', 'MONTH'))
        OR (scope = 'CHANNEL' AND channel_id IS NOT NULL AND content_type IS NULL AND period_type IN ('DAY', 'MONTH'))
        OR (scope = 'CONTENT_TYPE' AND content_type IS NOT NULL AND period_type = 'LIFETIME')),
    CONSTRAINT ck_budget_policy_no_content CHECK (content_id IS NULL),
    CONSTRAINT ck_budget_policy_content_type CHECK (content_type IS NULL OR content_type IN ('SHORTS', 'LONGFORM')),
    CONSTRAINT ck_budget_policy_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_budget_policy_revision CHECK (current_revision_no >= 1),
    INDEX ix_budget_policy_lookup (scope, channel_id, status)
);

-- 정책 revision: 불변. timezone_effective_from = 이 revision 시간대의 효력 시각(ADR-049).
-- 시간대 변경 revision은 변경 시점이 속한 기존 시간대 기간의 끝, 한도만 바꾼 revision은 직전 값을 그대로 가진다.
CREATE TABLE budget_policy_revision (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    policy_id               BIGINT        NOT NULL,
    revision_no             INT           NOT NULL,
    limit_krw               DECIMAL(24,6) NOT NULL,
    timezone                VARCHAR(64)   NOT NULL COMMENT 'IANA. SYSTEM 기본 Asia/Seoul',
    timezone_effective_from DATETIME(6)   NOT NULL,
    change_note             VARCHAR(500)  NULL,
    created_by              BIGINT        NOT NULL,
    created_at              DATETIME(6)   NOT NULL,
    CONSTRAINT pk_budget_policy_revision PRIMARY KEY (id),
    CONSTRAINT uk_budget_policy_revision UNIQUE (policy_id, revision_no),
    CONSTRAINT fk_budget_policy_revision_policy FOREIGN KEY (policy_id) REFERENCES budget_policy (id),
    CONSTRAINT fk_budget_policy_revision_creator FOREIGN KEY (created_by) REFERENCES admin_user (id),
    CONSTRAINT ck_budget_policy_revision_no CHECK (revision_no >= 1),
    CONSTRAINT ck_budget_policy_revision_limit CHECK (limit_krw >= 0)
);

CREATE TRIGGER trg_budget_policy_revision_no_update BEFORE UPDATE ON budget_policy_revision FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'budget_policy_revision is immutable';
CREATE TRIGGER trg_budget_policy_revision_no_delete BEFORE DELETE ON budget_policy_revision FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'budget_policy_revision is immutable';

-- 기간 bucket: 잠금·집계용. 같은 정책·대상 bucket은 겹치지 않는다(정책 행 잠금 하 생성, UNIQUE는 최종 방어).
CREATE TABLE budget_bucket (
    id                       BIGINT        NOT NULL AUTO_INCREMENT,
    policy_id                BIGINT        NOT NULL,
    content_id               BIGINT        NULL COMMENT 'CONTENT_TYPE/CONTENT LIFETIME bucket 대상(T03)',
    subject_key              BIGINT        AS (IFNULL(content_id, 0)) PERSISTENT,
    period_start             DATETIME(6)   NOT NULL,
    period_end               DATETIME(6)   NOT NULL,
    timezone                 VARCHAR(64)   NOT NULL COMMENT '경계 계산에 사용한 시간대(불변)',
    limit_krw                DECIMAL(24,6) NOT NULL,
    limit_policy_revision_no INT           NOT NULL COMMENT 'limit_krw를 가져온 정책 revision',
    reserved_krw             DECIMAL(24,6) NOT NULL DEFAULT 0,
    spent_krw                DECIMAL(24,6) NOT NULL DEFAULT 0,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,
    CONSTRAINT pk_budget_bucket PRIMARY KEY (id),
    CONSTRAINT uk_budget_bucket_period UNIQUE (policy_id, subject_key, period_start),
    CONSTRAINT fk_budget_bucket_policy FOREIGN KEY (policy_id) REFERENCES budget_policy (id),
    CONSTRAINT ck_budget_bucket_period CHECK (period_end > period_start),
    CONSTRAINT ck_budget_bucket_amounts CHECK (limit_krw >= 0 AND reserved_krw >= 0 AND spent_krw >= 0),
    CONSTRAINT ck_budget_bucket_no_content CHECK (content_id IS NULL),
    INDEX ix_budget_bucket_current (policy_id, subject_key, period_end)
);

-- 예약: 현재 상태(ADR-026, ADR-028). 환율은 예약 시점 값 고정(ADR-027).
CREATE TABLE budget_reservation (
    id                       BIGINT         NOT NULL AUTO_INCREMENT,
    request_key              VARCHAR(128)   COLLATE utf8mb4_bin NOT NULL,
    request_fingerprint      CHAR(64)       COLLATE utf8mb4_bin NOT NULL COMMENT '정규화 요청 SHA-256 hex',
    channel_id               BIGINT         NULL,
    content_id               BIGINT         NULL COMMENT 'T03에서 content FK 추가',
    purpose                  VARCHAR(64)    NOT NULL,
    original_currency        CHAR(3)        NOT NULL,
    estimate_original        DECIMAL(24,12) NOT NULL,
    exchange_rate_id         BIGINT         NULL,
    fx_rate                  DECIMAL(24,10) NOT NULL,
    estimate_krw             DECIMAL(24,6)  NOT NULL COMMENT 'CEILING(estimate_original*fx_rate, 6)',
    status                   VARCHAR(32)    NOT NULL,
    reserved_remaining_krw   DECIMAL(24,6)  NOT NULL,
    recognized_cost_krw      DECIMAL(24,6)  NOT NULL DEFAULT 0,
    recognized_cost_original DECIMAL(24,12) NOT NULL DEFAULT 0 COMMENT '원본 통화 인식 비용. KRW 전용 조정은 불변(ADR-057)',
    reconciliation_reason    VARCHAR(64)    NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    closed_at                DATETIME(6)    NULL,
    CONSTRAINT pk_budget_reservation PRIMARY KEY (id),
    CONSTRAINT uk_budget_reservation_request UNIQUE (request_key),
    CONSTRAINT fk_budget_reservation_channel FOREIGN KEY (channel_id) REFERENCES channel (id),
    CONSTRAINT fk_budget_reservation_rate FOREIGN KEY (exchange_rate_id) REFERENCES exchange_rate (id),
    CONSTRAINT ck_budget_reservation_key CHECK (request_key REGEXP '^[A-Za-z0-9:._-]{8,128}$'),
    CONSTRAINT ck_budget_reservation_fingerprint CHECK (request_fingerprint REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_budget_reservation_no_content CHECK (content_id IS NULL),
    CONSTRAINT ck_budget_reservation_currency CHECK (original_currency REGEXP '(?-i)^[A-Z]{3}$'),
    CONSTRAINT ck_budget_reservation_fx CHECK (
        (original_currency = 'KRW' AND exchange_rate_id IS NULL AND fx_rate = 1)
        OR (original_currency <> 'KRW' AND exchange_rate_id IS NOT NULL AND fx_rate > 0)),
    CONSTRAINT ck_budget_reservation_amounts CHECK (estimate_original > 0 AND estimate_krw > 0
        AND reserved_remaining_krw >= 0 AND recognized_cost_krw >= 0 AND recognized_cost_original >= 0),
    CONSTRAINT ck_budget_reservation_status CHECK (status IN ('ACTIVE', 'RECONCILIATION_REQUIRED', 'SETTLED', 'RELEASED')),
    CONSTRAINT ck_budget_reservation_closed CHECK ((status IN ('SETTLED', 'RELEASED')) = (closed_at IS NOT NULL)),
    CONSTRAINT ck_budget_reservation_closed_reserve CHECK (status NOT IN ('SETTLED', 'RELEASED') OR reserved_remaining_krw = 0),
    CONSTRAINT ck_budget_reservation_released_cost CHECK (status <> 'RELEASED' OR recognized_cost_krw = 0),
    INDEX ix_budget_reservation_status (status, created_at),
    INDEX ix_budget_reservation_channel (channel_id, created_at)
);

-- 예약↔bucket 연결: 불변. 지연 정산은 이 연결의 bucket에 반영(ADR-038).
CREATE TABLE budget_reservation_bucket (
    reservation_id BIGINT NOT NULL,
    bucket_id      BIGINT NOT NULL,
    CONSTRAINT pk_budget_reservation_bucket PRIMARY KEY (reservation_id, bucket_id),
    CONSTRAINT fk_brb_reservation FOREIGN KEY (reservation_id) REFERENCES budget_reservation (id),
    CONSTRAINT fk_brb_bucket FOREIGN KEY (bucket_id) REFERENCES budget_bucket (id),
    INDEX ix_brb_bucket (bucket_id)
);

CREATE TRIGGER trg_brb_no_update BEFORE UPDATE ON budget_reservation_bucket FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'budget_reservation_bucket is immutable';
CREATE TRIGGER trg_brb_no_delete BEFORE DELETE ON budget_reservation_bucket FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'budget_reservation_bucket is immutable';

-- 비용 원장: append-only 이벤트(ADR-019, ADR-026). 집계 기준은 reserve_delta_krw / cost_delta_krw / cost_delta_original.
CREATE TABLE cost_log (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    event_key           VARCHAR(128)   COLLATE utf8mb4_bin NOT NULL,
    event_fingerprint   CHAR(64)       COLLATE utf8mb4_bin NOT NULL,
    reservation_id      BIGINT         NOT NULL,
    request_key         VARCHAR(128)   COLLATE utf8mb4_bin NOT NULL COMMENT 'budget_reservation.request_key 복제(조회용)',
    entry_type          VARCHAR(16)    NOT NULL,
    reason_code         VARCHAR(32)    NULL COMMENT '예: FX_DIFFERENCE, PROVIDER_INVOICE, LATE_CHARGE, MANUAL_CORRECTION',
    original_currency   CHAR(3)        NOT NULL COMMENT '이벤트 금액의 통화(예약 통화 또는 KRW 전용 조정의 KRW)',
    original_amount     DECIMAL(24,12) NOT NULL COMMENT '이벤트 금액(부호 포함)',
    fx_rate             DECIMAL(24,10) NOT NULL COMMENT '예약 시점 고정 환율(KRW 이벤트는 1)',
    exchange_rate_id    BIGINT         NULL,
    amount_krw          DECIMAL(24,6)  NOT NULL COMMENT '이벤트 금액 KRW 값(저장 후 재환산 금지)',
    reserve_delta_krw   DECIMAL(24,6)  NOT NULL COMMENT 'reserved 집계 변화',
    cost_delta_krw      DECIMAL(24,6)  NOT NULL COMMENT 'spent·recognized_cost_krw 변화',
    cost_delta_original DECIMAL(24,12) NOT NULL COMMENT 'recognized_cost_original 변화(KRW 전용 조정은 0, ADR-057)',
    price_version       VARCHAR(64)    NULL,
    external_ref        VARCHAR(128)   NULL COMMENT 'provider 요청/청구 ID',
    actor_type          VARCHAR(16)    NOT NULL,
    actor_user_id       BIGINT         NULL,
    trace_id            VARCHAR(64)    NULL,
    occurred_at         DATETIME(6)    NOT NULL COMMENT '외부에서 발생한 시각',
    recorded_at         DATETIME(6)    NOT NULL,
    CONSTRAINT pk_cost_log PRIMARY KEY (id),
    CONSTRAINT uk_cost_log_event UNIQUE (event_key),
    CONSTRAINT fk_cost_log_reservation FOREIGN KEY (reservation_id) REFERENCES budget_reservation (id),
    CONSTRAINT fk_cost_log_rate FOREIGN KEY (exchange_rate_id) REFERENCES exchange_rate (id),
    CONSTRAINT fk_cost_log_actor FOREIGN KEY (actor_user_id) REFERENCES admin_user (id),
    CONSTRAINT ck_cost_log_event_key CHECK (event_key REGEXP '^[A-Za-z0-9:._-]{8,128}$'),
    CONSTRAINT ck_cost_log_fingerprint CHECK (event_fingerprint REGEXP '(?-i)^[0-9a-f]{64}$'),
    CONSTRAINT ck_cost_log_entry_type CHECK (entry_type IN ('RESERVE', 'RELEASE', 'PROVISIONAL', 'CONFIRM', 'ADJUSTMENT', 'REFUND')),
    CONSTRAINT ck_cost_log_reason CHECK (reason_code IS NULL OR reason_code REGEXP '(?-i)^[A-Z_]{2,32}$'),
    CONSTRAINT ck_cost_log_currency CHECK (original_currency REGEXP '(?-i)^[A-Z]{3}$'),
    CONSTRAINT ck_cost_log_actor_type CHECK (actor_type IN ('ADMIN', 'CLI', 'SYSTEM')),
    CONSTRAINT ck_cost_log_fx CHECK (fx_rate > 0 AND (original_currency <> 'KRW' OR (fx_rate = 1 AND exchange_rate_id IS NULL))),
    CONSTRAINT ck_cost_log_deltas CHECK (
        (entry_type = 'RESERVE' AND reserve_delta_krw > 0 AND cost_delta_krw = 0 AND cost_delta_original = 0)
        OR (entry_type = 'RELEASE' AND reserve_delta_krw <= 0 AND cost_delta_krw = 0 AND cost_delta_original = 0)
        OR (entry_type = 'PROVISIONAL')
        OR (entry_type = 'CONFIRM' AND reserve_delta_krw <= 0)
        OR (entry_type = 'ADJUSTMENT' AND reserve_delta_krw = 0)
        OR (entry_type = 'REFUND' AND reserve_delta_krw = 0 AND cost_delta_krw < 0 AND cost_delta_original <= 0)),
    INDEX ix_cost_log_reservation (reservation_id, id),
    INDEX ix_cost_log_request (request_key),
    INDEX ix_cost_log_type_time (entry_type, recorded_at),
    INDEX ix_cost_log_recorded (recorded_at)
);

CREATE TRIGGER trg_cost_log_no_update BEFORE UPDATE ON cost_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cost_log is append-only';
CREATE TRIGGER trg_cost_log_no_delete BEFORE DELETE ON cost_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cost_log is append-only';
