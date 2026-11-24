-- V3__create_admin_and_audit (T02, ADR-026~061). 설계: docs/design/T02_DATA_MODEL.md. 적용 후 수정 금지(ADR-009).
-- CHARSET/COLLATE 생략으로 DB 기본 utf8mb4_unicode_ci 상속. utf8mb4_bin은 정확 비교 컬럼만(ADR-053).

CREATE TABLE admin_user (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    login               VARCHAR(64)  NOT NULL COMMENT '소문자 정규화 로그인 ID(대소문자 무시 유일)',
    password_hash       CHAR(60)     COLLATE utf8mb4_bin NOT NULL COMMENT 'BCrypt 해시',
    credential_version  INT          NOT NULL DEFAULT 1 COMMENT '비밀번호 변경·비활성화 시 증가, 세션 무효화 기준(ADR-060)',
    role                VARCHAR(16)  NOT NULL,
    enabled             BOOLEAN      NOT NULL,
    password_changed_at DATETIME(6)  NOT NULL,
    last_login_at       DATETIME(6)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT pk_admin_user PRIMARY KEY (id),
    CONSTRAINT uk_admin_user_login UNIQUE (login),
    CONSTRAINT ck_admin_user_login CHECK (login REGEXP '(?-i)^[a-z0-9._-]{3,64}$'),
    CONSTRAINT ck_admin_user_hash CHECK (password_hash REGEXP '(?-i)^\\$2[aby]\\$[0-9]{2}\\$'),
    CONSTRAINT ck_admin_user_credential_version CHECK (credential_version >= 1),
    CONSTRAINT ck_admin_user_role CHECK (role IN ('ADMIN'))
);

-- 최초 관리자 생성 경쟁 방지: 단일 행(id=1). 최초 생성 트랜잭션이 함께 INSERT(ADR-031). 수정·삭제 금지(ADR-058).
CREATE TABLE admin_bootstrap (
    id            TINYINT     NOT NULL,
    admin_user_id BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    CONSTRAINT pk_admin_bootstrap PRIMARY KEY (id),
    CONSTRAINT ck_admin_bootstrap_singleton CHECK (id = 1),
    CONSTRAINT fk_admin_bootstrap_user FOREIGN KEY (admin_user_id) REFERENCES admin_user (id)
);

CREATE TRIGGER trg_admin_bootstrap_no_update BEFORE UPDATE ON admin_bootstrap FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'admin_bootstrap is immutable';
CREATE TRIGGER trg_admin_bootstrap_no_delete BEFORE DELETE ON admin_bootstrap FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'admin_bootstrap is immutable';

-- 감사 이벤트: append-only. 기본 보존 90일은 정책으로만 정의하고 삭제는 별도 관리 절차(ADR-051).
CREATE TABLE audit_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at   DATETIME(6)  NOT NULL,
    actor_type    VARCHAR(16)  NOT NULL,
    actor_user_id BIGINT       NULL,
    action        VARCHAR(64)  NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    entity_type   VARCHAR(64)  NULL,
    entity_id     VARCHAR(64)  NULL,
    trace_id      VARCHAR(64)  NULL,
    client_ip     VARCHAR(45)  NULL COMMENT 'request.getRemoteAddr()만 사용(ADR-045)',
    details_json  JSON         NULL COMMENT '비핵심 부가정보. 비밀번호·토큰·세션 ID 저장 금지',
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_user_id) REFERENCES admin_user (id),
    CONSTRAINT ck_audit_event_actor_type CHECK (actor_type IN ('ADMIN', 'CLI', 'SYSTEM', 'ANONYMOUS')),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    INDEX ix_audit_event_entity (entity_type, entity_id, occurred_at),
    INDEX ix_audit_event_occurred (occurred_at),
    INDEX ix_audit_event_actor (actor_user_id, occurred_at)
);

CREATE TRIGGER trg_audit_event_no_update BEFORE UPDATE ON audit_event FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
CREATE TRIGGER trg_audit_event_no_delete BEFORE DELETE ON audit_event FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_event is append-only';
