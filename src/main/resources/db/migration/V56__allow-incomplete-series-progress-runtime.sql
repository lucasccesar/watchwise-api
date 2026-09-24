ALTER TABLE series_progress_metadata
    ALTER COLUMN total_known_runtime DROP NOT NULL;

ALTER TABLE series_progress_season_metadata
    ALTER COLUMN total_known_runtime DROP NOT NULL;
