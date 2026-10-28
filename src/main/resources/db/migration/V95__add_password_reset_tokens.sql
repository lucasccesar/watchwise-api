ALTER TABLE users
    ADD COLUMN password_reset_token_hash VARCHAR(64),
    ADD COLUMN password_reset_expires_at TIMESTAMP WITHOUT TIME ZONE;

CREATE UNIQUE INDEX uq_users_password_reset_token_hash
    ON users (password_reset_token_hash)
    WHERE password_reset_token_hash IS NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_password_reset_token_state
        CHECK ((password_reset_token_hash IS NULL AND password_reset_expires_at IS NULL)
            OR (password_reset_token_hash IS NOT NULL AND password_reset_expires_at IS NOT NULL));
