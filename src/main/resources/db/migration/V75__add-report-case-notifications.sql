ALTER TABLE notifications DROP CONSTRAINT ck_notifications_social_shape;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_social_shape CHECK (
        (type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
            AND content_id IS NULL
            AND target_type IS NOT NULL
            AND target_id IS NOT NULL
            AND interaction_count > 0)
        OR
        (type IN ('FOLLOW_REQUEST', 'NEW_FOLLOWER', 'FOLLOW_REQUEST_ACCEPTED')
            AND content_id IS NULL
            AND target_type IS NULL
            AND target_id IS NULL
            AND interaction_count = 1)
        OR
        (type = 'REPORT_RESOLVED'
            AND content_id IS NULL
            AND target_type = 'REPORT_CASE'
            AND target_id IS NOT NULL
            AND actor_user_id IS NULL
            AND interaction_count = 1)
        OR
        (type NOT IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED',
                      'FOLLOW_REQUEST', 'NEW_FOLLOWER', 'FOLLOW_REQUEST_ACCEPTED', 'REPORT_RESOLVED')
            AND content_id IS NOT NULL
            AND target_type IS NULL
            AND target_id IS NULL)
    );
