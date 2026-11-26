CREATE TABLE production_discovery (
    production_request_id BIGINT NOT NULL PRIMARY KEY,
    selection_mode VARCHAR(8) NOT NULL,
    candidate_count TINYINT UNSIGNED NOT NULL,
    language VARCHAR(16) NOT NULL,
    rules_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    provider_operation_id BIGINT NULL UNIQUE,
    response_json JSON NULL,
    response_hash CHAR(64) NULL,
    generated_at DATETIME(6) NULL,
    evaluation_json JSON NULL,
    evaluated_at DATETIME(6) NULL,
    chosen_candidate_id TINYINT UNSIGNED NULL,
    choice_version INT UNSIGNED NULL,
    chosen_by BIGINT NULL,
    chosen_at DATETIME(6) NULL,
    selected_candidate_id TINYINT UNSIGNED NULL,
    selection_reason VARCHAR(2000) NULL,
    selected_at DATETIME(6) NULL,
    CONSTRAINT fk_pd_request FOREIGN KEY (production_request_id) REFERENCES production_request (id),
    CONSTRAINT fk_pd_operation FOREIGN KEY (provider_operation_id) REFERENCES provider_operation (id),
    CONSTRAINT fk_pd_chooser FOREIGN KEY (chosen_by) REFERENCES admin_user (id),
    CONSTRAINT ck_pd_mode CHECK (selection_mode IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_pd_count CHECK (candidate_count BETWEEN 5 AND 10),
    CONSTRAINT ck_pd_rules CHECK (rules_version > 0),
    CONSTRAINT ck_pd_response CHECK ((response_json IS NULL) = (response_hash IS NULL)
        AND (response_json IS NULL) = (generated_at IS NULL)
        AND (response_json IS NULL) = (provider_operation_id IS NULL)
        AND (response_json IS NULL OR (OCTET_LENGTH(response_json) <= 32768
            AND response_hash REGEXP '(?-i)^[0-9a-f]{64}$'))),
    CONSTRAINT ck_pd_evaluation CHECK ((evaluation_json IS NULL) = (evaluated_at IS NULL)
        AND (evaluation_json IS NULL OR (response_json IS NOT NULL AND JSON_TYPE(evaluation_json) = 'ARRAY'
            AND JSON_LENGTH(evaluation_json) = candidate_count))),
    CONSTRAINT ck_pd_choice CHECK ((chosen_candidate_id IS NULL) = (choice_version IS NULL)
        AND (chosen_candidate_id IS NULL) = (chosen_by IS NULL)
        AND (chosen_candidate_id IS NULL) = (chosen_at IS NULL)
        AND (chosen_candidate_id IS NULL OR (selection_mode = 'MANUAL' AND evaluation_json IS NOT NULL
            AND chosen_candidate_id BETWEEN 1 AND candidate_count AND choice_version < version))),
    CONSTRAINT ck_pd_selected CHECK ((selected_candidate_id IS NULL) = (selection_reason IS NULL)
        AND (selected_candidate_id IS NULL) = (selected_at IS NULL)
        AND (selected_candidate_id IS NULL OR (evaluation_json IS NOT NULL
            AND selected_candidate_id BETWEEN 1 AND candidate_count
            AND (selection_mode = 'AUTO' OR (chosen_candidate_id IS NOT NULL
                AND selected_candidate_id = chosen_candidate_id)))))
);
