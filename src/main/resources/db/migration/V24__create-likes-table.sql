CREATE TABLE likes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    comment_id UUID,
    diary_entry_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_likes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_likes_comment FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_likes_diary_entry FOREIGN KEY (diary_entry_id) REFERENCES diary_entries (id) ON DELETE CASCADE,
    CONSTRAINT ck_likes_target CHECK (
        (comment_id IS NOT NULL AND diary_entry_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NOT NULL)
    ),
    CONSTRAINT uq_likes_user_id_comment_id UNIQUE (user_id, comment_id),
    CONSTRAINT uq_likes_user_id_diary_entry_id UNIQUE (user_id, diary_entry_id)
);

CREATE INDEX idx_likes_user_id ON likes (user_id);
CREATE INDEX idx_likes_comment_id ON likes (comment_id);
CREATE INDEX idx_likes_diary_entry_id ON likes (diary_entry_id);
