ALTER TABLE users
    ADD COLUMN pending_email VARCHAR(60),
    ADD CONSTRAINT ck_users_pending_email_differs_from_current
        CHECK (pending_email IS NULL OR (
            is_email_verified IS TRUE
            AND BTRIM(pending_email) <> ''
            AND LOWER(pending_email) <> LOWER(email)
        ));

CREATE UNIQUE INDEX uq_users_effective_email
    ON users (LOWER(COALESCE(pending_email, email)));

CREATE INDEX idx_users_pending_email
    ON users (LOWER(pending_email))
    WHERE pending_email IS NOT NULL;
