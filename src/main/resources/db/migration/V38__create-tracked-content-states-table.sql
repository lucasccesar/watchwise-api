CREATE TABLE tracked_content_states (
    id UUID PRIMARY KEY,
    content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    last_known_release_date DATE,
    last_known_status VARCHAR(30),
    next_episode_air_date DATE,
    next_episode_season_number INTEGER,
    next_episode_number INTEGER,
    last_checked_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_tracked_content_states_content_id UNIQUE (content_id)
);
