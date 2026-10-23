ALTER TABLE contents ADD CONSTRAINT uq_contents_id_type UNIQUE (id, type);
ALTER TABLE diary_entries ADD COLUMN watched_at TIMESTAMPTZ;
ALTER TABLE calendar_schedule_completeness
    ADD COLUMN scope_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE content_ratings (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id UUID NOT NULL,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('SERIES', 'SEASON')),
    score INTEGER NOT NULL CHECK (score BETWEEN 1 AND 10),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_ratings_content_type FOREIGN KEY (content_id, content_type) REFERENCES contents(id, type),
    CONSTRAINT uq_content_ratings_owner_content UNIQUE (user_id, content_id)
);
CREATE INDEX idx_content_ratings_content ON content_ratings(content_id);

CREATE TABLE content_reviews (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id UUID NOT NULL,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('SERIES', 'SEASON')),
    publication_kind VARCHAR(30) NOT NULL CHECK (publication_kind IN ('WRITTEN_REVIEW', 'LEGACY_ACTIVITY')),
    context_kind VARCHAR(20) NOT NULL CHECK (context_kind IN ('UNKNOWN', 'MANUAL', 'COMPLETION', 'LEGACY')),
    context_json TEXT,
    comment TEXT,
    score_at_publication INTEGER CHECK (score_at_publication BETWEEN 1 AND 10),
    watch_number INTEGER CHECK (watch_number > 0),
    source_published_at TIMESTAMPTZ,
    source_published_on DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_reviews_content_type FOREIGN KEY (content_id, content_type) REFERENCES contents(id, type),
    CONSTRAINT ck_content_reviews_written_text CHECK (publication_kind <> 'WRITTEN_REVIEW' OR (comment IS NOT NULL AND btrim(comment) <> '')),
    CONSTRAINT ck_content_reviews_written_watch_number CHECK (publication_kind <> 'WRITTEN_REVIEW' OR watch_number IS NOT NULL)
);
CREATE UNIQUE INDEX uq_content_reviews_season_pass ON content_reviews(user_id, content_id, watch_number)
    WHERE publication_kind = 'WRITTEN_REVIEW' AND content_type = 'SEASON';
CREATE INDEX idx_content_reviews_owner_content ON content_reviews(user_id, content_id, created_at DESC);

CREATE TABLE diary_completion_projections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_id UUID NOT NULL,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('SERIES', 'SEASON')),
    watch_number INTEGER NOT NULL CHECK (watch_number > 0),
    anchor_diary_entry_id UUID REFERENCES diary_entries(id) ON DELETE SET NULL,
    completed_on DATE,
    ordered_at TIMESTAMPTZ NOT NULL,
    history_revision BIGINT NOT NULL,
    schedule_version BIGINT NOT NULL,
    valid_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_diary_completion_projections_content_type FOREIGN KEY (content_id, content_type) REFERENCES contents(id, type),
    CONSTRAINT uq_diary_completion_projections_pass UNIQUE (user_id, content_id, watch_number)
);
CREATE INDEX idx_diary_completion_projections_owner ON diary_completion_projections(user_id, ordered_at DESC);

CREATE TABLE user_series_history_states (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    series_tmdb_id VARCHAR(20) NOT NULL,
    history_revision BIGINT NOT NULL DEFAULT 0,
    computed_revision BIGINT NOT NULL DEFAULT 0,
    schedule_version BIGINT NOT NULL DEFAULT 0,
    valid_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, series_tmdb_id)
);

CREATE TABLE legacy_diary_snapshots (
    id UUID PRIMARY KEY,
    old_diary_entry_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    original_content_id UUID NOT NULL REFERENCES contents(id),
    original_content_type VARCHAR(20) NOT NULL,
    score INTEGER,
    comment TEXT,
    watched_date DATE,
    watched_at TIMESTAMPTZ,
    watch_number INTEGER,
    watched_in_theater BOOLEAN,
    auto_generated BOOLEAN NOT NULL,
    ignored BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    companions_json TEXT,
    flags_json TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE legacy_diary_migration_maps (
    id UUID PRIMARY KEY,
    old_diary_entry_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    destination_kind VARCHAR(40) NOT NULL CHECK (destination_kind IN ('CONTENT_RATING', 'CONTENT_REVIEW', 'DIARY_COMPLETION_PROJECTION', 'LEGACY_SNAPSHOT')),
    destination_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_legacy_diary_migration_maps_destination UNIQUE (destination_kind, destination_id)
);

CREATE TABLE user_action_receipts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation VARCHAR(40) NOT NULL CHECK (operation IN ('DIARY_BULK', 'CONTENT_REVIEW_PUBLICATION')),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    result_json TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_action_receipts_scope UNIQUE (user_id, operation, idempotency_key)
);
