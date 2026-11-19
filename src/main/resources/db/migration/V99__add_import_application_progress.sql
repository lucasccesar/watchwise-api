ALTER TABLE import_jobs
    ADD COLUMN progress_total_items BIGINT,
    ADD COLUMN progress_applied_items BIGINT,
    ADD COLUMN progress_skipped_items BIGINT;

ALTER TABLE import_jobs
    ADD CONSTRAINT ck_import_jobs_progress_counts
    CHECK (
        (progress_total_items IS NULL
            AND progress_applied_items IS NULL
            AND progress_skipped_items IS NULL)
        OR
        (progress_total_items IS NOT NULL
            AND progress_applied_items IS NOT NULL
            AND progress_skipped_items IS NOT NULL
            AND progress_total_items >= 0
            AND progress_applied_items >= 0
            AND progress_skipped_items >= 0
            AND progress_applied_items <= progress_total_items
            AND progress_skipped_items <= progress_total_items
            AND progress_applied_items <= progress_total_items - progress_skipped_items)
    );
