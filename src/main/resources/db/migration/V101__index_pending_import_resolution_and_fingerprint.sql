ALTER TABLE import_items
    ADD COLUMN review_fingerprint VARCHAR(64);

CREATE INDEX idx_import_items_resolution_pending
    ON import_items (job_id, plan_generation, ordinal, id)
    WHERE outcome IS NULL OR outcome = 'UNAVAILABLE';
