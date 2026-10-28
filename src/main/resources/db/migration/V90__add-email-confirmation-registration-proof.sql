ALTER TABLE users
    ADD COLUMN email_confirmation_registration_hash VARCHAR(64),
    ADD COLUMN email_confirmation_registration_required BOOLEAN NOT NULL DEFAULT FALSE;
