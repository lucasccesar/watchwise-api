CREATE TABLE series_progress_metadata (
    series_tmdb_id VARCHAR(20) PRIMARY KEY,
    regular_released_episode_count INTEGER NOT NULL,
    total_known_runtime INTEGER NOT NULL,
    known_runtime_episode_count INTEGER NOT NULL,
    last_released_episode_date DATE,
    refreshed_at TIMESTAMP NOT NULL,
    runtime_verified_at TIMESTAMP,
    CONSTRAINT ck_series_progress_metadata_non_negative_values
        CHECK (
            regular_released_episode_count >= 0
            AND total_known_runtime >= 0
            AND known_runtime_episode_count >= 0
        )
);

CREATE INDEX idx_series_progress_metadata_series_tmdb_id
    ON series_progress_metadata (series_tmdb_id);

CREATE INDEX idx_series_progress_metadata_refreshed_at
    ON series_progress_metadata (refreshed_at);

CREATE TABLE series_progress_season_metadata (
    id UUID PRIMARY KEY,
    series_tmdb_id VARCHAR(20) NOT NULL,
    season_number INTEGER NOT NULL,
    regular_released_episode_count INTEGER NOT NULL,
    total_known_runtime INTEGER NOT NULL,
    known_runtime_episode_count INTEGER NOT NULL,
    last_released_episode_date DATE,
    refreshed_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_series_progress_season_metadata_positive_season
        CHECK (season_number > 0),
    CONSTRAINT ck_series_progress_season_metadata_non_negative_values
        CHECK (
            regular_released_episode_count >= 0
            AND total_known_runtime >= 0
            AND known_runtime_episode_count >= 0
        ),
    CONSTRAINT uq_series_progress_season_metadata_identity
        UNIQUE (series_tmdb_id, season_number)
);

CREATE INDEX idx_series_progress_season_metadata_series_tmdb_id
    ON series_progress_season_metadata (series_tmdb_id);

CREATE INDEX idx_series_progress_season_metadata_series_and_season
    ON series_progress_season_metadata (series_tmdb_id, season_number);

CREATE INDEX idx_series_progress_season_metadata_refreshed_at
    ON series_progress_season_metadata (refreshed_at);
