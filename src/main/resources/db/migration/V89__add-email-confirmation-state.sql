ALTER TABLE users
    ALTER COLUMN is_email_verified SET DEFAULT FALSE,
    ADD COLUMN email_confirmation_token_hash VARCHAR(64),
    ADD COLUMN email_confirmation_expires_at TIMESTAMP WITHOUT TIME ZONE;

CREATE UNIQUE INDEX idx_users_email_confirmation_token_hash
    ON users (email_confirmation_token_hash)
    WHERE email_confirmation_token_hash IS NOT NULL;
