CREATE TABLE user_blocks (
  id UUID PRIMARY KEY,
  blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_user_blocks_blocker_id_blocked_id UNIQUE (blocker_id, blocked_id),
  CONSTRAINT ck_user_blocks_no_self_block CHECK (blocker_id <> blocked_id)
);

CREATE INDEX idx_user_blocks_blocker_created_at ON user_blocks (blocker_id, created_at DESC, id DESC);
CREATE INDEX idx_user_blocks_blocked_id ON user_blocks (blocked_id);
