CREATE TABLE shared_limiter_state (
    kind VARCHAR(7) NOT NULL CHECK (kind IN ('request', 'lockout')),
    key_digest VARCHAR(64) NOT NULL CHECK (key_digest ~ '^[0-9a-f]{64}$'),
    request_count INTEGER NOT NULL CHECK (request_count > 0),
    window_end TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (kind, key_digest),
    CHECK (kind = 'lockout' OR blocked_until IS NULL)
);

CREATE INDEX idx_shared_limiter_expiry ON shared_limiter_state (kind, window_end);
