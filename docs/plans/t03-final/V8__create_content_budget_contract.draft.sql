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
INSERT INTO budget_policy_state_event (policy_generation,policy_id,revision_no,status,changed_at)
 SELECT 0,id,current_revision_no,status,UTC_TIMESTAMP(6) FROM budget_policy;
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

CREATE TRIGGER trg_content_budget_policy_no_update BEFORE UPDATE ON content_budget_policy FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_content_budget_policy_no_delete BEFORE DELETE ON content_budget_policy FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_budget_policy_state_event_no_update BEFORE UPDATE ON budget_policy_state_event FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_budget_policy_state_event_no_delete BEFORE DELETE ON budget_policy_state_event FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_reservation_policy_selection_no_update BEFORE UPDATE ON reservation_policy_selection FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_reservation_policy_selection_no_delete BEFORE DELETE ON reservation_policy_selection FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_reservation_expected_bucket_no_update BEFORE UPDATE ON reservation_expected_bucket FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

CREATE TRIGGER trg_reservation_expected_bucket_no_delete BEFORE DELETE ON reservation_expected_bucket FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable budget reservation evidence';

-- Administrator settlement and consistency history do not reference the queue.
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

CREATE TRIGGER trg_settlement_operation_no_delete BEFORE DELETE ON settlement_operation FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Settlement requests are retained';

CREATE TRIGGER trg_settlement_guard_no_delete BEFORE DELETE ON settlement_guard FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Clear the settlement guard pointer instead of deleting it';

CREATE TRIGGER trg_consistency_issue_no_update BEFORE UPDATE ON consistency_issue FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable consistency issue';

CREATE TRIGGER trg_consistency_issue_no_delete BEFORE DELETE ON consistency_issue FOR EACH ROW
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable consistency issue';

DELIMITER $$
CREATE TRIGGER trg_settlement_operation_before_update BEFORE UPDATE ON settlement_operation FOR EACH ROW
BEGIN
 IF OLD.state <> 'PENDING' OR NEW.state NOT IN ('COMMITTED','REJECTED','CANCELLED')
  OR NEW.version <> OLD.version + 1 OR NOT (
   BINARY NEW.operation_id <=> BINARY OLD.operation_id
   AND NEW.reservation_id <=> OLD.reservation_id
   AND BINARY NEW.creation_request_key <=> BINARY OLD.creation_request_key
   AND BINARY NEW.event_key <=> BINARY OLD.event_key
   AND BINARY NEW.fingerprint <=> BINARY OLD.fingerprint
   AND NEW.expected_reservation_version <=> OLD.expected_reservation_version
   AND BINARY NEW.prior_operation_id <=> BINARY OLD.prior_operation_id
   AND BINARY NEW.payload <=> BINARY OLD.payload
   AND NEW.requested_by <=> OLD.requested_by
   AND NEW.created_at <=> OLD.created_at
  ) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Settlement request and terminal receipt are immutable';
 END IF;
END$$
DELIMITER ;
