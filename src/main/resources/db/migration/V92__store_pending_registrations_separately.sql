CREATE TABLE pending_registrations (
    id UUID PRIMARY KEY,
    username VARCHAR(60) NOT NULL,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(60) NOT NULL,
    password_hash VARCHAR NOT NULL,
    description VARCHAR(280),
    profile_picture VARCHAR(2048) NOT NULL,
    banner VARCHAR(2048),
    is_profile_public BOOLEAN NOT NULL,
    confirmation_token_hash VARCHAR(64),
    registration_proof_hash VARCHAR(64),
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

INSERT INTO pending_registrations (
    id, username, name, email, password_hash, description, profile_picture, banner,
    is_profile_public, confirmation_token_hash, registration_proof_hash, expires_at,
    created_at, updated_at
)
SELECT id, username, name, email, password, description, profile_picture, banner,
       is_profile_public, email_confirmation_token_hash, email_confirmation_registration_hash,
       email_confirmation_expires_at, created_at, updated_at
FROM users
WHERE is_email_verified IS FALSE
  AND email_confirmation_registration_required IS TRUE;

DELETE FROM users
WHERE is_email_verified IS FALSE
  AND email_confirmation_registration_required IS TRUE;

ALTER TABLE users
    DROP COLUMN email_confirmation_registration_hash,
    DROP COLUMN email_confirmation_registration_required;

CREATE UNIQUE INDEX uq_pending_registrations_confirmation_token
    ON pending_registrations (confirmation_token_hash)
    WHERE confirmation_token_hash IS NOT NULL;

CREATE INDEX idx_pending_registrations_email_expiry
    ON pending_registrations (LOWER(email), expires_at);

CREATE INDEX idx_pending_registrations_expiry
    ON pending_registrations (expires_at);
