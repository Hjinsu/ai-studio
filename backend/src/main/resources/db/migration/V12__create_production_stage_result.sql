CREATE TABLE production_stage_result (
    operation_id BIGINT NOT NULL PRIMARY KEY,
    request_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    schema_version INT NOT NULL,
    input_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    input_json JSON NOT NULL,
    body_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    body_json JSON NULL,
    validation_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_psr_operation FOREIGN KEY (operation_id) REFERENCES provider_operation (id),
    CONSTRAINT fk_psr_step FOREIGN KEY (request_id, step_id)
        REFERENCES production_step (production_request_id, id),
    CONSTRAINT ck_psr_schema CHECK (schema_version > 0),
    CONSTRAINT ck_psr_input CHECK (input_hash REGEXP '^[0-9a-f]{64}$'
        AND input_hash = SHA2(input_json, 256) AND JSON_TYPE(input_json) = 'OBJECT'),
    CONSTRAINT ck_psr_body CHECK ((body_json IS NULL) = (body_hash IS NULL)
        AND (body_json IS NULL OR (body_hash REGEXP '^[0-9a-f]{64}$'
            AND body_hash = SHA2(body_json, 256) AND JSON_TYPE(body_json) = 'OBJECT'
            AND OCTET_LENGTH(body_json) <= 131072))),
    CONSTRAINT ck_psr_validation CHECK (validation_json IS NULL OR
        (body_json IS NOT NULL AND JSON_TYPE(validation_json) = 'OBJECT'))
);

DELIMITER $$
CREATE TRIGGER trg_psr_owner BEFORE INSERT ON production_stage_result FOR EACH ROW
BEGIN
    IF NOT EXISTS (SELECT 1 FROM provider_operation o
        JOIN production_step s ON s.id = o.production_step_id
        WHERE o.id = NEW.operation_id AND o.production_request_id = NEW.request_id
            AND o.production_step_id = NEW.step_id AND s.stage IN ('RESEARCH', 'SCRIPT', 'SCENES')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stage result owner mismatch';
    END IF;
END$$
CREATE TRIGGER trg_psr_immutable BEFORE UPDATE ON production_stage_result FOR EACH ROW
BEGIN
    IF NOT (OLD.operation_id = NEW.operation_id AND OLD.request_id = NEW.request_id
        AND OLD.step_id = NEW.step_id AND OLD.schema_version = NEW.schema_version
        AND OLD.input_hash = NEW.input_hash AND BINARY OLD.input_json = BINARY NEW.input_json
        AND OLD.created_at = NEW.created_at
        AND (OLD.body_hash IS NULL OR (OLD.body_hash <=> NEW.body_hash
            AND BINARY OLD.body_json <=> BINARY NEW.body_json))
        AND (OLD.validation_json IS NULL OR BINARY OLD.validation_json <=> BINARY NEW.validation_json)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stage result is immutable';
    END IF;
END$$
DELIMITER ;
