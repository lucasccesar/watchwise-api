CREATE TABLE watchlist_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    type VARCHAR(6) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_watchlist_entries_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_watchlist_entries_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT uq_watchlist_entries_user_id_type_position UNIQUE (user_id, type, position),
    CONSTRAINT uq_watchlist_entries_user_id_type_content_id UNIQUE (user_id, type, content_id),
    CONSTRAINT ck_watchlist_entries_type CHECK (type IN ('MOVIE', 'SERIES')),
    CONSTRAINT ck_watchlist_entries_position CHECK (position >= 1)
);

CREATE INDEX idx_watchlist_entries_user_id_type ON watchlist_entries (user_id, type);