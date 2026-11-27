ALTER TABLE production_request ADD CONSTRAINT ck_pr_real_media_limit
    CHECK (pipeline_version <> 'v2-media-2' OR (content_type = 'SHORTS' AND limit_krw <= 3000));

CREATE TABLE production_worker_exchange (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    production_request_id BIGINT NOT NULL,
    production_step_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    issued_epoch BIGINT NOT NULL,
    schema_version INT NOT NULL,
    input_json JSON NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    selection_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    draft_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
    applied_at DATETIME(6) NULL,
    CONSTRAINT fk_pwe_step FOREIGN KEY (production_request_id, production_step_id)
        REFERENCES production_step (production_request_id, id),
    UNIQUE KEY uk_pwe_attempt (production_step_id, attempt_no),
    CHECK (schema_version = 1 AND attempt_no >= 1 AND issued_epoch >= 1),
    CHECK (input_sha256 REGEXP '^[0-9a-f]{64}$' AND BINARY input_sha256 = BINARY SHA2(input_json, 256)
        AND JSON_TYPE(input_json) = 'OBJECT'),
    CHECK (selection_sha256 IS NULL OR selection_sha256 REGEXP '^[0-9a-f]{64}$'),
    CHECK (draft_sha256 IS NULL OR draft_sha256 REGEXP '^[0-9a-f]{64}$'),
    CHECK (result_sha256 IS NULL OR (draft_sha256 IS NOT NULL AND result_sha256 REGEXP '^[0-9a-f]{64}$')),
    CHECK (applied_at IS NULL OR result_sha256 IS NOT NULL)
) ENGINE=InnoDB;

CREATE TABLE production_provider_job (
    operation_id BIGINT NOT NULL PRIMARY KEY,
    provider_namespace VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_job_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    submission_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    next_poll_at DATETIME(6) NULL,
    CONSTRAINT fk_ppj_operation FOREIGN KEY (operation_id) REFERENCES provider_operation (id),
    UNIQUE KEY uk_ppj_identity (provider_namespace, provider_job_id),
    INDEX ix_ppj_poll (next_poll_at, operation_id),
    CHECK (LENGTH(provider_namespace) > 0 AND LENGTH(provider_job_id) > 0),
    CHECK (submission_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB;

DELIMITER $$
CREATE TRIGGER trg_ppj_insert BEFORE INSERT ON production_provider_job FOR EACH ROW
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM provider_operation o JOIN production_request r ON r.id = o.production_request_id
        WHERE o.id = NEW.operation_id AND o.dispatched_at IS NOT NULL AND o.status IN ('DISPATCHED', 'UNKNOWN')
          AND r.pipeline_version = 'v2-media-2'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Provider job requires a dispatched real media operation';
    END IF;
END$$

CREATE TRIGGER trg_ppj_update BEFORE UPDATE ON production_provider_job FOR EACH ROW
BEGIN
    IF NOT (NEW.operation_id <=> OLD.operation_id AND NEW.provider_namespace <=> OLD.provider_namespace
        AND NEW.provider_job_id <=> OLD.provider_job_id AND NEW.submitted_at <=> OLD.submitted_at
        AND NEW.submission_sha256 <=> OLD.submission_sha256) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Provider job identity is immutable';
    END IF;
END$$

CREATE TRIGGER trg_pwe_insert BEFORE INSERT ON production_worker_exchange FOR EACH ROW
BEGIN
    IF NEW.draft_sha256 IS NOT NULL OR NEW.result_sha256 IS NOT NULL OR NEW.applied_at IS NOT NULL
        OR NOT EXISTS (
            SELECT 1 FROM production_step s JOIN production_request r ON r.id = s.production_request_id
            WHERE s.id = NEW.production_step_id AND r.id = NEW.production_request_id
                AND r.pipeline_version = 'v2-media-2' AND s.stage IN ('DISCOVER','RESEARCH','SCRIPT','SCENES')
                AND s.status = 'RUNNING' AND s.attempts = NEW.attempt_no
                AND r.lease_epoch = NEW.issued_epoch AND r.lease_until > UTC_TIMESTAMP(6)
                AND r.cancel_requested_at IS NULL
                AND (r.selection_hash <=> NEW.selection_sha256)
                AND ((s.stage = 'DISCOVER') = (NEW.selection_sha256 IS NULL))
        ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid worker exchange owner';
    END IF;
END$$

CREATE TRIGGER trg_pwe_update BEFORE UPDATE ON production_worker_exchange FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.production_request_id <=> OLD.production_request_id
            AND NEW.production_step_id <=> OLD.production_step_id AND NEW.attempt_no <=> OLD.attempt_no
            AND NEW.issued_epoch <=> OLD.issued_epoch AND NEW.schema_version <=> OLD.schema_version
            AND BINARY NEW.input_json <=> BINARY OLD.input_json
            AND NEW.input_sha256 <=> OLD.input_sha256 AND NEW.selection_sha256 <=> OLD.selection_sha256
            AND NEW.created_at <=> OLD.created_at)
        OR (OLD.draft_sha256 IS NOT NULL AND NOT (NEW.draft_sha256 <=> OLD.draft_sha256))
        OR (OLD.result_sha256 IS NOT NULL AND NOT (NEW.result_sha256 <=> OLD.result_sha256))
        OR (OLD.applied_at IS NOT NULL AND NOT (NEW.applied_at <=> OLD.applied_at)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Worker exchange identity and evidence are immutable';
    END IF;
END$$
DELIMITER ;

-- CONSTRAINT_3 is the generated name of the role CHECK on the V13 MariaDB table.
ALTER TABLE production_media_asset DROP CONSTRAINT CONSTRAINT_3,
    ADD CONSTRAINT ck_pma_role CHECK (role IN ('SOURCE_IMAGE', 'NORMALIZED_IMAGE', 'SOURCE_VIDEO',
        'NORMALIZED_VIDEO', 'SOURCE_AUDIO', 'PCM_AUDIO', 'ALIGNMENT', 'ASS', 'SRT', 'MP4', 'MEDIA_METADATA',
        'QC_REPORT', 'MANIFEST'));

DELIMITER $$
CREATE OR REPLACE TRIGGER trg_pmi_insert BEFORE INSERT ON production_media_item FOR EACH ROW
BEGIN
    IF NEW.operation_id IS NOT NULL OR NOT EXISTS (
        SELECT 1 FROM production_step s JOIN production_request r ON r.id = s.production_request_id
        WHERE s.id = NEW.production_step_id AND s.production_request_id = NEW.production_request_id
          AND r.pipeline_version IN ('v2-media-1', 'v2-media-2')
          AND stage = IF(NEW.kind = 'PROFILE', 'IMAGE', NEW.kind)
          AND input_generation = NEW.generation
          AND (NEW.kind <> 'PROFILE' OR target_key = 'video')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid media item owner';
    END IF;
END$$

CREATE OR REPLACE TRIGGER trg_pma_insert BEFORE INSERT ON production_media_asset FOR EACH ROW
BEGIN
    IF NEW.state <> 'STAGED' OR NOT EXISTS (
        SELECT 1 FROM production_media_item i JOIN production_request r ON r.id = i.production_request_id
        WHERE i.id = NEW.item_id AND i.attempt_no = NEW.attempt_no
          AND i.state IN ('ACTIVE', 'PROVEN') AND (
            (i.kind = 'IMAGE' AND NEW.role IN ('SOURCE_IMAGE', 'NORMALIZED_IMAGE')) OR
            (i.kind = 'IMAGE' AND r.pipeline_version = 'v2-media-2'
                AND NEW.role IN ('SOURCE_VIDEO', 'NORMALIZED_VIDEO')) OR
            (i.kind = 'TTS' AND NEW.role IN ('SOURCE_AUDIO', 'PCM_AUDIO', 'ALIGNMENT')) OR
            (i.kind = 'SUBTITLE' AND NEW.role IN ('ASS', 'SRT')) OR
            (i.kind = 'RENDER' AND NEW.role = 'MP4') OR
            (i.kind = 'METADATA' AND NEW.role = 'MEDIA_METADATA') OR
            (i.kind = 'QC' AND NEW.role IN ('QC_REPORT', 'MANIFEST')))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid media asset owner';
    END IF;
END$$
DELIMITER ;
