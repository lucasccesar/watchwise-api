CREATE TABLE daily_challenges (
    id UUID NOT NULL,
    challenge_date DATE NOT NULL,
    game_type VARCHAR(40) NOT NULL,
    target_kind VARCHAR(10) NOT NULL,
    target_tmdb_id VARCHAR(20),
    series_tmdb_id VARCHAR(20),
    season_number INTEGER,
    episode_number INTEGER,
    answer_key VARCHAR(256) NOT NULL,
    source_tmdb_id VARCHAR(20),
    image_path VARCHAR(500) NOT NULL,
    answer_snapshot JSONB NOT NULL,
    display_snapshot JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_daily_challenges PRIMARY KEY (id),
    CONSTRAINT uq_daily_challenges_date_game UNIQUE (challenge_date, game_type),
    CONSTRAINT uq_daily_challenges_game_answer UNIQUE (game_type, answer_key),
    CONSTRAINT ck_daily_challenges_game_type CHECK (
        game_type IN (
            'MOVIE_BY_POSTER',
            'SERIES_BY_POSTER',
            'PERSON_BY_FACE',
            'EPISODE_BY_FRAME',
            'MOVIE_BY_INFO',
            'SERIES_BY_INFO',
            'ACTOR_BY_MOVIE_FILMOGRAPHY',
            'ACTOR_BY_SERIES_FILMOGRAPHY'
        )
    ),
    CONSTRAINT ck_daily_challenges_target_kind CHECK (
        target_kind IN ('MOVIE', 'SERIES', 'PERSON', 'EPISODE')
    ),
    CONSTRAINT ck_daily_challenges_game_target_kind CHECK (
        game_type NOT IN (
            'MOVIE_BY_POSTER',
            'SERIES_BY_POSTER',
            'PERSON_BY_FACE',
            'EPISODE_BY_FRAME',
            'MOVIE_BY_INFO',
            'SERIES_BY_INFO',
            'ACTOR_BY_MOVIE_FILMOGRAPHY',
            'ACTOR_BY_SERIES_FILMOGRAPHY'
        )
        OR target_kind NOT IN ('MOVIE', 'SERIES', 'PERSON', 'EPISODE')
        OR (game_type IN ('MOVIE_BY_POSTER', 'MOVIE_BY_INFO') AND target_kind = 'MOVIE')
        OR (game_type IN ('SERIES_BY_POSTER', 'SERIES_BY_INFO') AND target_kind = 'SERIES')
        OR (game_type = 'PERSON_BY_FACE' AND target_kind = 'PERSON')
        OR (game_type = 'EPISODE_BY_FRAME' AND target_kind = 'EPISODE')
        OR (game_type IN ('ACTOR_BY_MOVIE_FILMOGRAPHY', 'ACTOR_BY_SERIES_FILMOGRAPHY')
            AND target_kind = 'PERSON')
    ),
    CONSTRAINT ck_daily_challenges_coordinates CHECK (
        target_kind NOT IN ('MOVIE', 'SERIES', 'PERSON', 'EPISODE')
        OR
        (
            target_kind IN ('MOVIE', 'SERIES', 'PERSON')
            AND target_tmdb_id IS NOT NULL
            AND series_tmdb_id IS NULL
            AND season_number IS NULL
            AND episode_number IS NULL
        )
        OR (
            target_kind = 'EPISODE'
            AND target_tmdb_id IS NULL
            AND series_tmdb_id IS NOT NULL
            AND season_number IS NOT NULL
            AND episode_number IS NOT NULL
        )
    ),
    CONSTRAINT ck_daily_challenges_positive_coordinates CHECK (
        (target_tmdb_id IS NULL OR length(trim(target_tmdb_id)) > 0)
        AND (series_tmdb_id IS NULL OR length(trim(series_tmdb_id)) > 0)
        AND (season_number IS NULL OR season_number > 0)
        AND (episode_number IS NULL OR episode_number > 0)
    )
);

CREATE TABLE daily_challenge_hints (
    id UUID NOT NULL,
    daily_challenge_id UUID NOT NULL,
    position INTEGER NOT NULL,
    hint_type VARCHAR(50) NOT NULL,
    hint_value TEXT NOT NULL,
    CONSTRAINT pk_daily_challenge_hints PRIMARY KEY (id),
    CONSTRAINT fk_daily_challenge_hints_challenge FOREIGN KEY (daily_challenge_id)
        REFERENCES daily_challenges(id) ON DELETE CASCADE,
    CONSTRAINT uq_daily_challenge_hints_position UNIQUE (daily_challenge_id, position),
    CONSTRAINT ck_daily_challenge_hints_positive_position CHECK (position > 0)
);

CREATE TABLE user_daily_game_results (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    daily_challenge_id UUID NOT NULL,
    attempts_used INTEGER NOT NULL DEFAULT 0,
    score INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_user_daily_game_results PRIMARY KEY (id),
    CONSTRAINT fk_user_daily_game_results_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_daily_game_results_challenge FOREIGN KEY (daily_challenge_id)
        REFERENCES daily_challenges(id) ON DELETE CASCADE,
    CONSTRAINT uq_user_daily_game_results_user_challenge UNIQUE (user_id, daily_challenge_id),
    CONSTRAINT ck_user_daily_game_results_non_negative CHECK (attempts_used >= 0 AND score >= 0),
    CONSTRAINT ck_user_daily_game_results_status CHECK (
        status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')
    )
);

CREATE INDEX idx_user_daily_game_results_challenge_score_attempts
    ON user_daily_game_results (daily_challenge_id, score, attempts_used);

CREATE INDEX idx_user_daily_game_results_user_status_challenge
    ON user_daily_game_results (user_id, status, daily_challenge_id);
