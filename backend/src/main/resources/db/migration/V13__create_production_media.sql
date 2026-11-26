CREATE TABLE production_media_item (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    production_request_id BIGINT NOT NULL,
    production_step_id BIGINT NOT NULL,
    target_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation INT NOT NULL,
    ordinal INT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    input_json JSON NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    attempt_no INT NOT NULL DEFAULT 0,
    operation_id BIGINT NULL,
    execution_proof_json JSON NULL,
    execution_proof_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recovery_deadline_at DATETIME(6) NULL,
    failure_code VARCHAR(48) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (production_request_id, production_step_id) REFERENCES production_step (production_request_id, id),
    FOREIGN KEY (operation_id) REFERENCES provider_operation (id),
    UNIQUE KEY uk_pmi_target (production_request_id, production_step_id, target_key, generation),
    UNIQUE KEY uk_pmi_operation (operation_id),
    INDEX ix_pmi_step (production_request_id, production_step_id, state, ordinal, id),
    INDEX ix_pmi_recovery (state, recovery_deadline_at),
    CHECK (generation >= 1 AND ordinal >= 0 AND lease_epoch >= 0 AND attempt_no >= 0),
    CHECK (input_sha256 REGEXP '^[0-9a-f]{64}$' AND BINARY input_sha256 = BINARY SHA2(input_json, 256)
        AND JSON_TYPE(input_json) = 'OBJECT'),
    CHECK (kind IN ('PROFILE', 'IMAGE', 'TTS', 'SUBTITLE', 'RENDER', 'METADATA', 'QC')),
    CHECK (state IN ('PENDING', 'ACTIVE', 'PROVEN', 'FINALIZED', 'APPLIED', 'FAILED', 'CANCELLED')),
    CHECK ((kind = 'PROFILE') = (target_key = 'PROFILE')),
    CHECK ((kind = 'PROFILE' AND ordinal = 0 AND generation = 1 AND state = 'APPLIED'
        AND lease_epoch = 0 AND attempt_no = 0 AND operation_id IS NULL) OR (kind <> 'PROFILE' AND ordinal >= 1)),
    CHECK (operation_id IS NULL OR kind IN ('IMAGE', 'TTS')),
    CHECK ((execution_proof_json IS NULL) = (execution_proof_sha256 IS NULL)),
    CHECK ((execution_proof_json IS NULL) = (recovery_deadline_at IS NULL)),
    CHECK (execution_proof_json IS NULL OR (operation_id IS NOT NULL
        AND execution_proof_sha256 REGEXP '^[0-9a-f]{64}$'
        AND BINARY execution_proof_sha256 = BINARY SHA2(execution_proof_json, 256) AND JSON_TYPE(execution_proof_json) = 'OBJECT'))
) ENGINE=InnoDB;

CREATE TABLE production_media_asset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    role VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_no INT NOT NULL,
    writer_epoch BIGINT NOT NULL,
    relative_path VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mime_type VARCHAR(96) NOT NULL,
    byte_length BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lineage_json JSON NOT NULL,
    rights_json JSON NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    finalized_at DATETIME(6) NULL,
    final_role VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN state = 'FINAL' THEN role END) PERSISTENT,
    FOREIGN KEY (item_id) REFERENCES production_media_item (id),
    UNIQUE KEY uk_pma_path (relative_path),
    UNIQUE KEY uk_pma_attempt (item_id, attempt_no, role),
    UNIQUE KEY uk_pma_final (item_id, final_role),
    INDEX ix_pma_state (item_id, state, role),
    CHECK (attempt_no >= 1 AND writer_epoch >= 1 AND byte_length > 0),
    CHECK (sha256 REGEXP '^[0-9a-f]{64}$'),
    CHECK (role IN ('SOURCE_IMAGE', 'NORMALIZED_IMAGE', 'SOURCE_AUDIO', 'PCM_AUDIO', 'ALIGNMENT',
        'ASS', 'SRT', 'MP4', 'MEDIA_METADATA', 'QC_REPORT', 'MANIFEST')),
    CHECK (state IN ('STAGED', 'FINAL', 'ABANDONED')),
    CHECK ((state = 'FINAL') = (finalized_at IS NOT NULL))
) ENGINE=InnoDB;

DELIMITER $$
CREATE TRIGGER trg_pmi_insert BEFORE INSERT ON production_media_item FOR EACH ROW
BEGIN
    IF NEW.operation_id IS NOT NULL OR NOT EXISTS (
        SELECT 1 FROM production_step s JOIN production_request r ON r.id = s.production_request_id
        WHERE s.id = NEW.production_step_id AND s.production_request_id = NEW.production_request_id
          AND r.pipeline_version = 'v2-media-1'
          AND stage = IF(NEW.kind = 'PROFILE', 'IMAGE', NEW.kind)
          AND input_generation = NEW.generation
          AND (NEW.kind <> 'PROFILE' OR target_key = 'video')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid media item owner';
    END IF;
END$$

CREATE TRIGGER trg_pmi_update BEFORE UPDATE ON production_media_item FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.production_request_id <=> OLD.production_request_id
            AND NEW.production_step_id <=> OLD.production_step_id AND NEW.target_key <=> OLD.target_key
            AND NEW.generation <=> OLD.generation AND NEW.ordinal <=> OLD.ordinal AND NEW.kind <=> OLD.kind
            AND BINARY NEW.input_json <=> BINARY OLD.input_json AND NEW.input_sha256 <=> OLD.input_sha256
            AND NEW.created_at <=> OLD.created_at)
        OR NEW.lease_epoch < OLD.lease_epoch OR NEW.attempt_no < OLD.attempt_no
        OR (OLD.state <> NEW.state AND NOT (
            (OLD.state = 'PENDING' AND NEW.state = 'ACTIVE') OR
            (OLD.state = 'ACTIVE' AND NEW.state IN ('PROVEN', 'APPLIED', 'FAILED', 'CANCELLED')) OR
            (OLD.state = 'PROVEN' AND NEW.state IN ('FINALIZED', 'FAILED', 'CANCELLED')) OR
            (OLD.state = 'FINALIZED' AND NEW.state IN ('APPLIED', 'FAILED', 'CANCELLED'))))
        OR (OLD.operation_id IS NOT NULL AND NOT (NEW.operation_id <=> OLD.operation_id))
        OR (OLD.execution_proof_sha256 IS NOT NULL AND NOT
            (NEW.execution_proof_sha256 <=> OLD.execution_proof_sha256
             AND BINARY NEW.execution_proof_json <=> BINARY OLD.execution_proof_json
             AND NEW.recovery_deadline_at <=> OLD.recovery_deadline_at))
        OR (NEW.operation_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM provider_operation WHERE id = NEW.operation_id
              AND production_request_id = NEW.production_request_id
              AND production_step_id = NEW.production_step_id
        )) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid media item update';
    END IF;
END$$

CREATE TRIGGER trg_pma_insert BEFORE INSERT ON production_media_asset FOR EACH ROW
BEGIN
    IF NEW.state <> 'STAGED' OR NOT EXISTS (
        SELECT 1 FROM production_media_item i WHERE i.id = NEW.item_id AND i.attempt_no = NEW.attempt_no
          AND i.state IN ('ACTIVE', 'PROVEN') AND (
            (i.kind = 'IMAGE' AND NEW.role IN ('SOURCE_IMAGE', 'NORMALIZED_IMAGE')) OR
            (i.kind = 'TTS' AND NEW.role IN ('SOURCE_AUDIO', 'PCM_AUDIO', 'ALIGNMENT')) OR
            (i.kind = 'SUBTITLE' AND NEW.role IN ('ASS', 'SRT')) OR
            (i.kind = 'RENDER' AND NEW.role = 'MP4') OR
            (i.kind = 'METADATA' AND NEW.role = 'MEDIA_METADATA') OR
            (i.kind = 'QC' AND NEW.role IN ('QC_REPORT', 'MANIFEST')))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid media asset owner';
    END IF;
END$$

CREATE TRIGGER trg_pma_update BEFORE UPDATE ON production_media_asset FOR EACH ROW
BEGIN
    IF NOT (NEW.id <=> OLD.id AND NEW.item_id <=> OLD.item_id AND NEW.role <=> OLD.role
            AND NEW.attempt_no <=> OLD.attempt_no AND NEW.writer_epoch <=> OLD.writer_epoch
            AND NEW.relative_path <=> OLD.relative_path AND BINARY NEW.mime_type <=> BINARY OLD.mime_type
            AND NEW.byte_length <=> OLD.byte_length AND NEW.sha256 <=> OLD.sha256
            AND BINARY NEW.lineage_json <=> BINARY OLD.lineage_json
            AND BINARY NEW.rights_json <=> BINARY OLD.rights_json AND NEW.created_at <=> OLD.created_at)
        OR (OLD.state <> 'STAGED' AND NOT (NEW.state <=> OLD.state AND NEW.finalized_at <=> OLD.finalized_at)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid media asset update';
    END IF;
END$$
DELIMITER ;
