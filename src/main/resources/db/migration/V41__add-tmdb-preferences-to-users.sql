ALTER TABLE users
    ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'en-US',
    ADD COLUMN preferred_region VARCHAR(2) NOT NULL DEFAULT 'US';
