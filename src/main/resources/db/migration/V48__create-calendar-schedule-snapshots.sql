CREATE TABLE calendar_schedule_snapshots (
    id UUID PRIMARY KEY,
    event_type VARCHAR(10) NOT NULL,
    tmdb_id VARCHAR(20),
    series_tmdb_id VARCHAR(20),
    season_number INTEGER,
    episode_number INTEGER,
    region VARCHAR(2) NOT NULL,
    language VARCHAR(10) NOT NULL,
    release_date DATE,
    title VARCHAR(500) NOT NULL,
    series_title VARCHAR(500),
    poster_path VARCHAR(500),
    still_path VARCHAR(500),
    last_checked_at TIMESTAMP NOT NULL,
    next_check_at TIMESTAMP NOT NULL,
    present_in_last_tmdb_snapshot BOOLEAN NOT NULL,
    CONSTRAINT ck_calendar_schedule_snapshots_event_type
        CHECK (event_type IN ('MOVIE', 'EPISODE')),
    CONSTRAINT ck_calendar_schedule_snapshots_coordinates
        CHECK (
            (event_type = 'MOVIE'
                AND tmdb_id IS NOT NULL
                AND series_tmdb_id IS NULL
                AND season_number IS NULL
                AND episode_number IS NULL)
            OR
            (event_type = 'EPISODE'
                AND tmdb_id IS NULL
                AND series_tmdb_id IS NOT NULL
                AND season_number IS NOT NULL
                AND episode_number IS NOT NULL)
        ),
    CONSTRAINT ck_calendar_schedule_snapshots_positive_coordinates
        CHECK (
            event_type = 'MOVIE'
            OR (season_number > 0 AND episode_number > 0)
        )
);

CREATE UNIQUE INDEX uq_calendar_schedule_snapshots_movie_identity
    ON calendar_schedule_snapshots (event_type, tmdb_id, region, language)
    WHERE event_type = 'MOVIE';

CREATE UNIQUE INDEX uq_calendar_schedule_snapshots_episode_identity
    ON calendar_schedule_snapshots (event_type, series_tmdb_id, season_number, episode_number, region, language)
    WHERE event_type = 'EPISODE';

CREATE INDEX idx_calendar_schedule_snapshots_next_check_at
    ON calendar_schedule_snapshots (next_check_at);

CREATE INDEX idx_calendar_schedule_snapshots_release_date_locale
    ON calendar_schedule_snapshots (release_date, region, language);

CREATE INDEX idx_calendar_schedule_snapshots_tmdb_id
    ON calendar_schedule_snapshots (tmdb_id);

CREATE INDEX idx_calendar_schedule_snapshots_series_coordinates
    ON calendar_schedule_snapshots (series_tmdb_id, season_number, episode_number);
