ALTER TABLE picks_templates
    ADD COLUMN likes_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE picks
    ADD COLUMN likes_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE likes
    ADD COLUMN pick_id UUID,
    ADD COLUMN picks_template_id UUID;

ALTER TABLE comments
    ADD COLUMN pick_id UUID,
    ADD COLUMN picks_template_id UUID;

ALTER TABLE likes
    ADD CONSTRAINT fk_likes_pick FOREIGN KEY (pick_id) REFERENCES picks (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_likes_picks_template FOREIGN KEY (picks_template_id) REFERENCES picks_templates (id) ON DELETE CASCADE;

ALTER TABLE comments
    ADD CONSTRAINT fk_comments_pick FOREIGN KEY (pick_id) REFERENCES picks (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_comments_picks_template FOREIGN KEY (picks_template_id) REFERENCES picks_templates (id) ON DELETE CASCADE;

ALTER TABLE likes
    DROP CONSTRAINT ck_likes_target;

ALTER TABLE likes
    ADD CONSTRAINT ck_likes_target CHECK (
        (comment_id IS NOT NULL AND diary_entry_id IS NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NOT NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND list_id IS NOT NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND list_id IS NULL AND pick_id IS NOT NULL AND picks_template_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND list_id IS NULL AND pick_id IS NULL AND picks_template_id IS NOT NULL)
    );

ALTER TABLE comments
    DROP CONSTRAINT ck_comments_target;

ALTER TABLE comments
    ADD CONSTRAINT ck_comments_target CHECK (
        (content_id IS NOT NULL AND list_id IS NULL AND diary_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NOT NULL AND diary_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NOT NULL AND pick_id IS NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND pick_id IS NOT NULL AND picks_template_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND pick_id IS NULL AND picks_template_id IS NOT NULL)
    );

ALTER TABLE likes
    ADD CONSTRAINT uq_likes_user_id_pick_id UNIQUE (user_id, pick_id),
    ADD CONSTRAINT uq_likes_user_id_picks_template_id UNIQUE (user_id, picks_template_id);

CREATE INDEX idx_likes_pick_id ON likes (pick_id);
CREATE INDEX idx_likes_picks_template_id ON likes (picks_template_id);
CREATE INDEX idx_comments_pick_id ON comments (pick_id);
CREATE INDEX idx_comments_picks_template_id ON comments (picks_template_id);
