CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_user_lists_name_trgm
    ON user_lists USING GIN (LOWER(name) gin_trgm_ops);

CREATE INDEX idx_users_username_lower_pattern
    ON users (LOWER(username) text_pattern_ops);
