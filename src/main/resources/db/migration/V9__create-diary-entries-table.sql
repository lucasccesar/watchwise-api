CREATE TABLE diary_entries (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    score INTEGER,
    comment TEXT,
    watched_date DATE,
    is_rewatch BOOLEAN NOT NULL DEFAULT FALSE,
    watched_in_theater BOOLEAN,
    custom_poster_url VARCHAR(2048),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_diary_entries_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_diary_entries_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT ck_diary_entries_score CHECK (score BETWEEN 1 AND 10)
);

CREATE INDEX idx_diary_entries_user_id ON diary_entries (user_id);
CREATE INDEX idx_diary_entries_user_id_content_id ON diary_entries (user_id, content_id);