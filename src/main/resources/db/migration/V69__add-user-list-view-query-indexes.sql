CREATE INDEX idx_user_lists_user_updated_at_id
    ON user_lists (user_id, updated_at DESC, id DESC);

CREATE INDEX idx_user_lists_user_rank_id
    ON user_lists (user_id, rank ASC, id ASC);

CREATE INDEX idx_user_lists_public_discovery_order
    ON user_lists (visibility, likes_count DESC, created_at DESC, id DESC);
