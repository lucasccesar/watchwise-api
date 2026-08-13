CREATE TABLE top5_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    type VARCHAR(6) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_top5_entries_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_top5_entries_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT uq_top5_entries_user_id_type_position UNIQUE (user_id, type, position),
    CONSTRAINT uq_top5_entries_user_id_type_content_id UNIQUE (user_id, type, content_id),
    CONSTRAINT ck_top5_entries_type CHECK (type IN ('MOVIE', 'SERIES')),
    CONSTRAINT ck_top5_entries_position CHECK (position BETWEEN 1 AND 5)
);

CREATE INDEX idx_top5_entries_user_id_type ON top5_entries (user_id, type);