ALTER TABLE content_ratings
    ADD COLUMN watch_number INTEGER,
    ADD CONSTRAINT ck_content_ratings_watch_number
        CHECK (watch_number IS NULL OR watch_number > 0);

CREATE TABLE content_rating_passes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id UUID NOT NULL,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('SERIES', 'SEASON')),
    watch_number INTEGER,
    score INTEGER NOT NULL CHECK (score BETWEEN 1 AND 10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_content_rating_passes_watch_number
        CHECK (watch_number IS NULL OR watch_number > 0),
    CONSTRAINT fk_content_rating_passes_content_type
        FOREIGN KEY (content_id, content_type) REFERENCES contents(id, type)
);

CREATE UNIQUE INDEX uq_content_rating_passes_owner_content_pass
    ON content_rating_passes(user_id, content_id, watch_number)
    WHERE watch_number IS NOT NULL;

CREATE UNIQUE INDEX uq_content_rating_passes_legacy
    ON content_rating_passes(user_id, content_id)
    WHERE watch_number IS NULL;

INSERT INTO content_rating_passes(
    id, user_id, content_id, content_type, watch_number, score, created_at, updated_at)
SELECT gen_random_uuid(), user_id, content_id, content_type, NULL, score, created_at, updated_at
FROM content_ratings;
