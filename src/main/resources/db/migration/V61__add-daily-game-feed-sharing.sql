ALTER TABLE user_daily_game_results
    ADD COLUMN share_on_completion BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN shared_at TIMESTAMP;

ALTER TABLE user_daily_game_results
    ADD CONSTRAINT ck_user_daily_game_results_shared_terminal CHECK (
        shared_at IS NULL OR status IN ('COMPLETED', 'FAILED')
    );

CREATE INDEX idx_user_daily_game_results_feed_shared
    ON user_daily_game_results (user_id, shared_at DESC, id DESC)
    WHERE shared_at IS NOT NULL;
