CREATE TABLE calendar_schedule_completeness (
    id UUID PRIMARY KEY,
    group_type VARCHAR(10) NOT NULL,
    series_tmdb_id VARCHAR(20) NOT NULL,
    season_number INTEGER,
    region VARCHAR(2) NOT NULL,
    language VARCHAR(10) NOT NULL,
    expected_episode_count INTEGER NOT NULL,
    complete BOOLEAN NOT NULL,
    last_checked_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_calendar_schedule_completeness_group_type
        CHECK (group_type IN ('SEASON', 'SERIES')),
    CONSTRAINT ck_calendar_schedule_completeness_coordinates
        CHECK (
            (group_type = 'SEASON' AND season_number IS NOT NULL AND season_number > 0)
            OR (group_type = 'SERIES' AND season_number IS NULL)
        )
);

CREATE UNIQUE INDEX uq_calendar_schedule_completeness_season_identity
    ON calendar_schedule_completeness (group_type, series_tmdb_id, season_number, region, language)
    WHERE group_type = 'SEASON';

CREATE UNIQUE INDEX uq_calendar_schedule_completeness_series_identity
    ON calendar_schedule_completeness (group_type, series_tmdb_id, region, language)
    WHERE group_type = 'SERIES';
