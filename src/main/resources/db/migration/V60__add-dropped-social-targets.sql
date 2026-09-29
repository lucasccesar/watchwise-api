ALTER TABLE dropped_entries
    ADD COLUMN likes_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE comments
    ADD COLUMN dropped_entry_id UUID;

ALTER TABLE likes
    ADD COLUMN dropped_entry_id UUID;

ALTER TABLE comments
    ADD CONSTRAINT fk_comments_dropped_entry
        FOREIGN KEY (dropped_entry_id) REFERENCES dropped_entries (id) ON DELETE CASCADE;

ALTER TABLE likes
    ADD CONSTRAINT fk_likes_dropped_entry
        FOREIGN KEY (dropped_entry_id) REFERENCES dropped_entries (id) ON DELETE CASCADE;

ALTER TABLE comments
    DROP CONSTRAINT ck_comments_target;

ALTER TABLE comments
    ADD CONSTRAINT ck_comments_target CHECK (
        (content_id IS NOT NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NOT NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NOT NULL AND dropped_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NOT NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND pick_id IS NOT NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NOT NULL)
    );

ALTER TABLE likes
    DROP CONSTRAINT ck_likes_target;

ALTER TABLE likes
    ADD CONSTRAINT ck_likes_target CHECK (
        (comment_id IS NOT NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NOT NULL AND dropped_entry_id IS NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NOT NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NOT NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL AND pick_id IS NOT NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NOT NULL)
    );

ALTER TABLE likes
    ADD CONSTRAINT uq_likes_user_id_dropped_entry_id UNIQUE (user_id, dropped_entry_id);

CREATE INDEX idx_comments_dropped_entry_id ON comments (dropped_entry_id);
CREATE INDEX idx_likes_dropped_entry_id ON likes (dropped_entry_id);
