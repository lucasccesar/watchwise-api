CREATE TABLE contents (
    id UUID PRIMARY KEY,
    tmdb_id VARCHAR(20),
    type VARCHAR(20) NOT NULL,
    series_tmdb_id VARCHAR(20),
    season_number INTEGER,
    episode_number INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contents_tmdb_id_type UNIQUE (tmdb_id, type),
    CONSTRAINT ck_contents_type CHECK (type IN ('MOVIE', 'SERIES', 'SEASON', 'EPISODE')),
    CONSTRAINT ck_contents_fields_by_type CHECK (
        (type IN ('MOVIE', 'SERIES') AND tmdb_id IS NOT NULL AND series_tmdb_id IS NULL AND season_number IS NULL AND episode_number IS NULL)
        OR (type = 'SEASON' AND tmdb_id IS NULL AND series_tmdb_id IS NOT NULL AND season_number IS NOT NULL AND episode_number IS NULL)
        OR (type = 'EPISODE' AND tmdb_id IS NULL AND series_tmdb_id IS NOT NULL AND season_number IS NOT NULL AND episode_number IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_contents_series_season_episode
    ON contents (series_tmdb_id, season_number, COALESCE(episode_number, -1))
    WHERE type IN ('SEASON', 'EPISODE');
