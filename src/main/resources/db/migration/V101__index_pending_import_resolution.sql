CREATE INDEX idx_import_items_resolution_pending
    ON import_items (job_id, plan_generation, ordinal, id)
    WHERE outcome IS NULL OR outcome = 'UNAVAILABLE';
