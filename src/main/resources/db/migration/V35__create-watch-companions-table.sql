CREATE TABLE watch_companions (
    id UUID PRIMARY KEY,
    diary_entry_id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_watch_companions_diary_entry FOREIGN KEY (diary_entry_id) REFERENCES diary_entries (id) ON DELETE CASCADE,
    CONSTRAINT fk_watch_companions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_watch_companions_diary_entry_id_user_id UNIQUE (diary_entry_id, user_id)
);

CREATE INDEX idx_watch_companions_diary_entry_id ON watch_companions (diary_entry_id);
CREATE INDEX idx_watch_companions_user_id ON watch_companions (user_id);
