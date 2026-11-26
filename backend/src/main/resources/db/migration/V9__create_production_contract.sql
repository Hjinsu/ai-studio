CREATE TABLE production_request (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    channel_config_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    content_type VARCHAR(16) NOT NULL,
    pipeline_version VARCHAR(32) NOT NULL,
    request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    limit_krw DECIMAL(24,6) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    content_id BIGINT NULL,
    run_id BIGINT NULL UNIQUE,
    selection_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    lease_owner VARCHAR(128) NULL,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    cancel_requested_at DATETIME(6) NULL,
    spending_blocked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pr_config FOREIGN KEY (channel_id, channel_config_id) REFERENCES channel_config (channel_id, id),
    CONSTRAINT fk_pr_actor FOREIGN KEY (requested_by) REFERENCES admin_user (id),
    CONSTRAINT fk_pr_content FOREIGN KEY (content_id, channel_id, content_type)
        REFERENCES content (id, channel_id, content_type),
    CONSTRAINT fk_pr_run FOREIGN KEY (content_id, run_id) REFERENCES content_run (content_id, id),
    CONSTRAINT ck_pr_type CHECK (content_type IN ('SHORTS', 'LONGFORM')),
    CONSTRAINT ck_pr_limit CHECK (limit_krw >= 0),
    CONSTRAINT ck_pr_status CHECK (status IN ('QUEUED', 'RUNNING', 'RECOVERY_REQUIRED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_pr_link CHECK ((content_id IS NULL) = (run_id IS NULL) AND (run_id IS NULL) = (selection_hash IS NULL)),
    CONSTRAINT ck_pr_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL) AND lease_epoch >= 0),
    INDEX ix_pr_claim (status, id),
    INDEX ix_pr_lease (lease_until)
);

CREATE TABLE production_step (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    production_request_id BIGINT NOT NULL,
    stage VARCHAR(16) NOT NULL,
    target_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT 'video',
    input_generation INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    retry_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    result_ref VARCHAR(512) NULL,
    last_error_code VARCHAR(64) NULL,
    CONSTRAINT fk_ps_request FOREIGN KEY (production_request_id) REFERENCES production_request (id),
    UNIQUE KEY uk_ps_input (production_request_id, stage, target_key, input_generation),
    UNIQUE KEY uk_ps_owner (production_request_id, id),
    CONSTRAINT ck_ps_stage CHECK (stage IN ('DISCOVER', 'SELECT', 'RESEARCH', 'SCRIPT', 'SCENES', 'IMAGE',
        'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC')),
    CONSTRAINT ck_ps_generation CHECK (input_generation >= 1),
    CONSTRAINT ck_ps_attempts CHECK (attempts BETWEEN 0 AND 3),
    CONSTRAINT ck_ps_status CHECK (status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'RECOVERY_REQUIRED',
        'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE provider_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    production_request_id BIGINT NOT NULL,
    production_step_id BIGINT NOT NULL,
    effect_key CHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    reservation_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    dispatched_at DATETIME(6) NULL,
    evidence_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    evidence_json JSON NULL,
    receipt_outcome VARCHAR(24) NULL,
    receipt_event_id BIGINT NULL,
    receipt_at DATETIME(6) NULL,
    applied_at DATETIME(6) NULL,
    CONSTRAINT fk_po_step FOREIGN KEY (production_request_id, production_step_id)
        REFERENCES production_step (production_request_id, id),
    CONSTRAINT fk_po_reservation FOREIGN KEY (reservation_id) REFERENCES budget_reservation (id),
    CONSTRAINT fk_po_receipt_event FOREIGN KEY (receipt_event_id) REFERENCES cost_log (id),
    CONSTRAINT ck_po_state CHECK (status IN ('RESERVED', 'DISPATCHED', 'UNKNOWN', 'SETTLED')),
    CONSTRAINT ck_po_dispatch CHECK (status NOT IN ('DISPATCHED', 'UNKNOWN') OR dispatched_at IS NOT NULL),
    CONSTRAINT ck_po_evidence CHECK ((evidence_hash IS NULL) = (evidence_json IS NULL)),
    CONSTRAINT ck_po_receipt CHECK ((status = 'SETTLED') = (receipt_at IS NOT NULL)
        AND (receipt_at IS NULL) = (receipt_event_id IS NULL)
        AND (receipt_at IS NULL) = (receipt_outcome IS NULL)
        AND (receipt_at IS NULL OR evidence_hash IS NOT NULL)
        AND (applied_at IS NULL OR receipt_at IS NOT NULL)),
    CONSTRAINT ck_po_outcome CHECK (receipt_outcome IN ('RESULT_ADOPTED', 'EXECUTED_FAILED', 'NOT_EXECUTED'))
);

DELIMITER $$
CREATE TRIGGER trg_provider_operation_immutable BEFORE UPDATE ON provider_operation FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.production_request_id <=> OLD.production_request_id
            AND NEW.production_step_id <=> OLD.production_step_id AND NEW.effect_key <=> OLD.effect_key
            AND NEW.request_hash <=> OLD.request_hash AND NEW.reservation_id <=> OLD.reservation_id)
            OR (OLD.dispatched_at IS NOT NULL AND NOT (NEW.dispatched_at <=> OLD.dispatched_at))
            OR (OLD.evidence_hash IS NOT NULL AND NOT (NEW.evidence_hash <=> OLD.evidence_hash
                AND BINARY NEW.evidence_json <=> BINARY OLD.evidence_json))
            OR (OLD.receipt_at IS NOT NULL AND NOT (NEW.receipt_at <=> OLD.receipt_at
                AND NEW.receipt_event_id <=> OLD.receipt_event_id AND NEW.receipt_outcome <=> OLD.receipt_outcome))
            OR (OLD.applied_at IS NOT NULL AND NOT (NEW.applied_at <=> OLD.applied_at)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'provider operation evidence and receipt are immutable';
    END IF;
END$$
DELIMITER ;
