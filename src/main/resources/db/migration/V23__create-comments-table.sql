CREATE TABLE comments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID,
    list_id UUID,
    diary_entry_id UUID,
    parent_comment_id UUID,
    text VARCHAR(280) NOT NULL,
    contains_spoiler BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_list FOREIGN KEY (list_id) REFERENCES user_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_diary_entry FOREIGN KEY (diary_entry_id) REFERENCES diary_entries (id) ON DELETE CASCADE,
    CONSTRAINT fk_comments_parent_comment FOREIGN KEY (parent_comment_id) REFERENCES comments (id) ON DELETE CASCADE,
    CONSTRAINT ck_comments_target CHECK (
        (content_id IS NOT NULL AND list_id IS NULL AND diary_entry_id IS NULL) OR
        (content_id IS NULL AND list_id IS NOT NULL AND diary_entry_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NOT NULL)
    )
);

CREATE INDEX idx_comments_user_id ON comments (user_id);
CREATE INDEX idx_comments_content_id ON comments (content_id);
CREATE INDEX idx_comments_list_id ON comments (list_id);
CREATE INDEX idx_comments_diary_entry_id ON comments (diary_entry_id);
CREATE INDEX idx_comments_parent_comment_id ON comments (parent_comment_id);
