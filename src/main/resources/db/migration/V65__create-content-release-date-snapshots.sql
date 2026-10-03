CREATE TABLE content_release_date_snapshots (
    id UUID PRIMARY KEY,
    tmdb_id VARCHAR(20) NOT NULL,
    type VARCHAR(6) NOT NULL,
    region VARCHAR(2),
    release_date DATE,
    status VARCHAR(11) NOT NULL,
    last_checked_at TIMESTAMP NOT NULL,
    next_check_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_content_release_date_snapshots_type CHECK (type IN ('MOVIE', 'SERIES')),
    CONSTRAINT ck_content_release_date_snapshots_region CHECK (
        (type = 'MOVIE' AND region IS NOT NULL)
        OR (type = 'SERIES' AND region IS NULL)
    ),
    CONSTRAINT ck_content_release_date_snapshots_status CHECK (status IN ('FOUND', 'NOT_FOUND', 'UNAVAILABLE'))
);

CREATE UNIQUE INDEX uq_content_release_date_snapshots_movie
    ON content_release_date_snapshots (tmdb_id, region)
    WHERE type = 'MOVIE';

CREATE UNIQUE INDEX uq_content_release_date_snapshots_series
    ON content_release_date_snapshots (tmdb_id)
    WHERE type = 'SERIES';

CREATE INDEX idx_content_release_date_snapshots_due
    ON content_release_date_snapshots (next_check_at);
