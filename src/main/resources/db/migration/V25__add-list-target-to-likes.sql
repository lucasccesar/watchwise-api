ALTER TABLE likes ADD COLUMN list_id UUID;

ALTER TABLE likes ADD CONSTRAINT fk_likes_list FOREIGN KEY (list_id) REFERENCES user_lists (id) ON DELETE CASCADE;

ALTER TABLE likes DROP CONSTRAINT ck_likes_target;

ALTER TABLE likes ADD CONSTRAINT ck_likes_target CHECK (
    (comment_id IS NOT NULL AND diary_entry_id IS NULL AND list_id IS NULL) OR
    (comment_id IS NULL AND diary_entry_id IS NOT NULL AND list_id IS NULL) OR
    (comment_id IS NULL AND diary_entry_id IS NULL AND list_id IS NOT NULL)
);

ALTER TABLE likes ADD CONSTRAINT uq_likes_user_id_list_id UNIQUE (user_id, list_id);

CREATE INDEX idx_likes_list_id ON likes (list_id);
