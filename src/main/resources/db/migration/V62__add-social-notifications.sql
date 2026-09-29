ALTER TABLE notifications
    ALTER COLUMN content_id DROP NOT NULL;

ALTER TABLE notifications
    ADD COLUMN actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN target_type VARCHAR(30),
    ADD COLUMN target_id UUID,
    ADD COLUMN interaction_count INTEGER NOT NULL DEFAULT 1;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_social_shape CHECK (
        (type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
            AND content_id IS NULL
            AND target_type IS NOT NULL
            AND target_id IS NOT NULL
            AND interaction_count > 0)
        OR
        (type NOT IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
            AND content_id IS NOT NULL
            AND target_type IS NULL
            AND target_id IS NULL)
    );

CREATE UNIQUE INDEX uq_notifications_social_aggregate
    ON notifications (user_id, type, target_type, target_id)
    WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED');

CREATE INDEX idx_notifications_social_retention
    ON notifications (type, is_read, updated_at, id)
    WHERE type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED');
