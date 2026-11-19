ALTER TABLE import_jobs
    ADD COLUMN resolution_progress_legacy BOOLEAN NOT NULL DEFAULT TRUE;
