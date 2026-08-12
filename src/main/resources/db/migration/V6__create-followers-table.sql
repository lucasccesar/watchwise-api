CREATE TABLE followers (
    id UUID PRIMARY KEY,
    follower_id UUID NOT NULL,
    followed_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_followers_follower FOREIGN KEY (follower_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_followers_followed FOREIGN KEY (followed_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_followers_follower_id_followed_id UNIQUE (follower_id, followed_id),
    CONSTRAINT ck_followers_no_self_follow CHECK (follower_id <> followed_id),
    CONSTRAINT ck_followers_status CHECK (status IN ('PENDING', 'ACCEPTED'))
);

CREATE INDEX idx_followers_followed_id_status ON followers (followed_id, status);
CREATE INDEX idx_followers_follower_id_status ON followers (follower_id, status);