ALTER TABLE import_jobs
    ADD COLUMN resolution_progress_phase VARCHAR(32),
    ADD COLUMN resolution_progress_generation BIGINT,
    ADD COLUMN resolution_progress_last_ordinal BIGINT,
    ADD COLUMN resolution_progress_total BIGINT,
    ADD COLUMN resolution_progress_examined BIGINT,
    ADD COLUMN review_required_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN review_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE import_jobs
    ADD CONSTRAINT ck_import_jobs_resolution_progress
    CHECK (
        (resolution_progress_phase IS NULL
            AND resolution_progress_generation IS NULL
            AND resolution_progress_last_ordinal IS NULL
            AND resolution_progress_total IS NULL
            AND resolution_progress_examined IS NULL)
        OR
        (resolution_progress_phase IS NOT NULL
            AND resolution_progress_generation IS NOT NULL
            AND resolution_progress_last_ordinal IS NOT NULL
            AND resolution_progress_total IS NOT NULL
            AND resolution_progress_examined IS NOT NULL
            AND resolution_progress_generation >= 0
            AND resolution_progress_last_ordinal >= -1
            AND resolution_progress_total >= 0
            AND resolution_progress_examined >= 0
            AND resolution_progress_examined <= resolution_progress_total)
    );

ALTER TABLE import_jobs
    ADD CONSTRAINT ck_import_jobs_review_counters
    CHECK (review_required_count >= 0 AND review_revision >= 0);

ALTER TABLE import_items
    ADD COLUMN review_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN review_revision BIGINT,
    ADD CONSTRAINT ck_import_items_review_revision
        CHECK (review_revision IS NULL OR review_revision >= 1);

CREATE INDEX idx_import_items_review_cursor
    ON import_items (job_id, plan_generation, review_revision, id)
    WHERE review_revision IS NOT NULL;
