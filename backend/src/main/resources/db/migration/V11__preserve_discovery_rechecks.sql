ALTER TABLE production_discovery
    ADD COLUMN recheck_json JSON NULL,
    ADD COLUMN rechecked_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_pd_recheck CHECK ((recheck_json IS NULL) = (rechecked_at IS NULL)
        AND (recheck_json IS NULL OR (evaluation_json IS NOT NULL AND JSON_TYPE(recheck_json) = 'ARRAY'
            AND JSON_LENGTH(recheck_json) = candidate_count)));
