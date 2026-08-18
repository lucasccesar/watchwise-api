CREATE TABLE dropped_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    type VARCHAR(6) NOT NULL,
    comment TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dropped_entries_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_dropped_entries_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT uq_dropped_entries_user_id_type_content_id UNIQUE (user_id, type, content_id),
    CONSTRAINT ck_dropped_entries_type CHECK (type IN ('MOVIE', 'SERIES'))
);

CREATE INDEX idx_dropped_entries_user_id_type ON dropped_entries (user_id, type);