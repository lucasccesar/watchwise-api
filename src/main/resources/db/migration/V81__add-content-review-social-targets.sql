ALTER TABLE comments ADD COLUMN content_review_id UUID;
ALTER TABLE comments
    ADD CONSTRAINT fk_comments_content_review
        FOREIGN KEY (content_review_id) REFERENCES content_reviews (id) ON DELETE CASCADE;
ALTER TABLE comments DROP CONSTRAINT ck_comments_target;
ALTER TABLE comments
    ADD CONSTRAINT ck_comments_target CHECK (
        (content_id IS NOT NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (content_id IS NULL AND list_id IS NOT NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NOT NULL AND dropped_entry_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NOT NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL
            AND pick_id IS NOT NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NOT NULL AND content_review_id IS NULL) OR
        (content_id IS NULL AND list_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NOT NULL)
    );

ALTER TABLE likes ADD COLUMN content_review_id UUID;
ALTER TABLE likes
    ADD CONSTRAINT fk_likes_content_review
        FOREIGN KEY (content_review_id) REFERENCES content_reviews (id) ON DELETE CASCADE;
ALTER TABLE likes DROP CONSTRAINT ck_likes_target;
ALTER TABLE likes
    ADD CONSTRAINT ck_likes_target CHECK (
        (comment_id IS NOT NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NOT NULL AND dropped_entry_id IS NULL AND list_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NOT NULL AND list_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NOT NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL
            AND pick_id IS NOT NULL AND picks_template_id IS NULL AND content_review_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NOT NULL AND content_review_id IS NULL) OR
        (comment_id IS NULL AND diary_entry_id IS NULL AND dropped_entry_id IS NULL AND list_id IS NULL
            AND pick_id IS NULL AND picks_template_id IS NULL AND content_review_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_likes_user_content_review
    ON likes (user_id, content_review_id)
    WHERE content_review_id IS NOT NULL;
CREATE INDEX idx_likes_content_review_id ON likes (content_review_id);
CREATE INDEX idx_comments_content_review_id_created_at ON comments (content_review_id, created_at);

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_target_type CHECK (
        target_type IS NULL OR target_type IN (
            'COMMENT', 'DIARY_ENTRY', 'DROPPED_ENTRY', 'USER_LIST', 'PICK',
            'PICKS_TEMPLATE', 'REPORT_CASE', 'CONTENT_REVIEW'));

ALTER TABLE report_cases DROP CONSTRAINT ck_report_cases_target_type;
ALTER TABLE report_cases
    ADD CONSTRAINT ck_report_cases_target_type CHECK (target_type IN (
        'USER', 'COMMENT', 'REVIEW', 'CONTENT_REVIEW', 'USER_LIST', 'PICK', 'PICKS_TEMPLATE', 'DROPPED_ENTRY'));
