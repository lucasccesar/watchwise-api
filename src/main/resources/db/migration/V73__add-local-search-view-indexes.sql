CREATE INDEX idx_users_username_trgm
    ON users USING GIN (LOWER(username) gin_trgm_ops);

CREATE INDEX idx_users_name_trgm
    ON users USING GIN (LOWER(name) gin_trgm_ops);

CREATE INDEX idx_picks_templates_name_trgm
    ON picks_templates USING GIN (LOWER(name) gin_trgm_ops);
