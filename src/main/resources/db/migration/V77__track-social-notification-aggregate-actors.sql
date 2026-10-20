CREATE TABLE notification_actors (
    notification_id UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT pk_notification_actors PRIMARY KEY (notification_id, actor_user_id)
);

CREATE INDEX idx_notification_actors_actor_notification
    ON notification_actors (actor_user_id, notification_id);

-- Historical aggregates retain only their latest actor, so multi-interaction rows cannot be attributed
-- reliably. Clear those ambiguous rows from inboxes that already participate in blocking before new
-- notifications start recording complete actor membership.
DELETE FROM notifications n
WHERE (
    n.type IN ('LIKE_RECEIVED', 'COMMENT_RECEIVED')
    AND n.interaction_count > 1
    AND NOT EXISTS (
        SELECT 1 FROM notification_actors a WHERE a.notification_id = n.id
    )
    AND EXISTS (
        SELECT 1 FROM user_blocks b
        WHERE b.blocker_id = n.user_id OR b.blocked_id = n.user_id
    )
)
OR EXISTS (
    SELECT 1 FROM user_blocks b
    WHERE (b.blocker_id = n.user_id AND b.blocked_id = n.actor_user_id)
       OR (b.blocker_id = n.actor_user_id AND b.blocked_id = n.user_id)
);
