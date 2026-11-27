ALTER TABLE production_discovery
    ADD worker_exchange_id BIGINT NULL UNIQUE,
    ADD CONSTRAINT fk_pd_worker FOREIGN KEY (worker_exchange_id) REFERENCES production_worker_exchange (id),
    DROP CONSTRAINT ck_pd_response,
    ADD CONSTRAINT ck_pd_result_owner CHECK (
        (response_json IS NULL AND provider_operation_id IS NULL AND worker_exchange_id IS NULL)
        OR (response_json IS NOT NULL AND ((provider_operation_id IS NULL) <> (worker_exchange_id IS NULL)))),
    ADD CONSTRAINT ck_pd_response_integrity CHECK (
        (response_json IS NULL) = (response_hash IS NULL) AND
        (response_json IS NULL) = (generated_at IS NULL) AND
        (response_json IS NULL OR (OCTET_LENGTH(response_json) <= 32768
            AND response_hash REGEXP '(?-i)^[0-9a-f]{64}$')));

ALTER TABLE production_stage_result ADD UNIQUE KEY uk_psr_operation (operation_id);
ALTER TABLE production_stage_result DROP PRIMARY KEY,
    MODIFY operation_id BIGINT NULL,
    ADD id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ADD UNIQUE KEY uk_psr_stage_owner (step_id),
    ADD worker_exchange_id BIGINT NULL UNIQUE,
    ADD CONSTRAINT fk_psr_worker FOREIGN KEY (worker_exchange_id) REFERENCES production_worker_exchange(id),
    ADD CONSTRAINT ck_psr_result_owner CHECK ((operation_id IS NULL) <> (worker_exchange_id IS NULL));

DELIMITER $$
CREATE TRIGGER trg_pd_no_initial_response BEFORE INSERT ON production_discovery FOR EACH ROW
BEGIN
    IF NEW.response_json IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Discovery response must be adopted after initialization';
    END IF;
END$$

CREATE TRIGGER trg_pd_result_owner BEFORE UPDATE ON production_discovery FOR EACH ROW
BEGIN
    IF OLD.response_json IS NOT NULL AND NOT (
        OLD.provider_operation_id <=> NEW.provider_operation_id AND OLD.worker_exchange_id <=> NEW.worker_exchange_id
        AND BINARY OLD.response_json <=> BINARY NEW.response_json
        AND OLD.response_hash <=> NEW.response_hash AND OLD.generated_at <=> NEW.generated_at
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Discovery result is immutable';
    END IF;
    IF OLD.response_json IS NULL AND NEW.provider_operation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
        WHERE o.id = NEW.provider_operation_id AND o.production_request_id = NEW.production_request_id
          AND s.production_request_id = NEW.production_request_id AND s.stage = 'DISCOVER'
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Discovery operation owner mismatch';
    END IF;
    IF OLD.response_json IS NULL AND NEW.worker_exchange_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM production_worker_exchange w
        JOIN production_request r ON r.id = w.production_request_id
        JOIN production_step s ON s.id = w.production_step_id AND s.production_request_id = r.id
        WHERE w.id = NEW.worker_exchange_id AND r.id = NEW.production_request_id AND s.stage = 'DISCOVER'
          AND s.attempts = w.attempt_no AND w.issued_epoch = r.lease_epoch
          AND r.lease_until > UTC_TIMESTAMP(6) AND r.cancel_requested_at IS NULL
          AND w.applied_at IS NOT NULL AND w.result_sha256 = NEW.response_hash
          AND BINARY SHA2(NEW.response_json, 256) = BINARY NEW.response_hash
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Discovery worker owner mismatch';
    END IF;
END$$

CREATE OR REPLACE TRIGGER trg_psr_owner BEFORE INSERT ON production_stage_result FOR EACH ROW
BEGIN
    IF NEW.operation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM provider_operation o JOIN production_step s ON s.id = o.production_step_id
        WHERE o.id = NEW.operation_id AND o.production_request_id = NEW.request_id
            AND o.production_step_id = NEW.step_id AND s.stage IN ('RESEARCH', 'SCRIPT', 'SCENES')
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Stage result operation owner mismatch';
    END IF;
    IF NEW.worker_exchange_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM production_worker_exchange w
        JOIN production_request r ON r.id = w.production_request_id
        JOIN production_step s ON s.id = w.production_step_id AND s.production_request_id = r.id
        WHERE w.id = NEW.worker_exchange_id AND r.id = NEW.request_id AND s.id = NEW.step_id
            AND s.stage IN ('RESEARCH', 'SCRIPT', 'SCENES') AND s.attempts = w.attempt_no
            AND r.lease_epoch = w.issued_epoch AND r.lease_until > UTC_TIMESTAMP(6)
            AND r.cancel_requested_at IS NULL AND (r.selection_hash <=> w.selection_sha256)
            AND w.applied_at IS NOT NULL AND w.input_sha256 = NEW.input_hash
            AND NEW.body_json IS NOT NULL AND NEW.validation_json IS NOT NULL
            AND w.result_sha256 = NEW.body_hash
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Stage result worker owner mismatch';
    END IF;
END$$

CREATE OR REPLACE TRIGGER trg_psr_immutable BEFORE UPDATE ON production_stage_result FOR EACH ROW
BEGIN
    IF NOT (OLD.id = NEW.id AND OLD.operation_id <=> NEW.operation_id
        AND OLD.worker_exchange_id <=> NEW.worker_exchange_id AND OLD.request_id = NEW.request_id
        AND OLD.step_id = NEW.step_id AND OLD.schema_version = NEW.schema_version
        AND OLD.input_hash = NEW.input_hash AND BINARY OLD.input_json = BINARY NEW.input_json
        AND OLD.created_at = NEW.created_at
        AND (OLD.body_hash IS NULL OR (OLD.body_hash <=> NEW.body_hash AND BINARY OLD.body_json <=> BINARY NEW.body_json))
        AND (OLD.validation_json IS NULL OR BINARY OLD.validation_json <=> BINARY NEW.validation_json)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Stage result is immutable';
    END IF;
END$$
DELIMITER ;
