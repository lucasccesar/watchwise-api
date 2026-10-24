CREATE INDEX idx_import_items_resolved_content_keyset
    ON import_items(job_id, plan_generation, resolved_content_key, ordinal, id)
    WHERE resolved_content_key IS NOT NULL;
