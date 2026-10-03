UPDATE watchlist_entries
SET position = position + 1000000000;

ALTER TABLE watchlist_entries
    DROP CONSTRAINT uq_watchlist_entries_user_id_type_position;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at ASC, id ASC) AS new_position
    FROM watchlist_entries
)
UPDATE watchlist_entries w
SET position = ranked.new_position
FROM ranked
WHERE w.id = ranked.id;

ALTER TABLE watchlist_entries
    ADD CONSTRAINT uq_watchlist_entries_user_id_position UNIQUE (user_id, position);

CREATE INDEX idx_watchlist_entries_user_id_position
    ON watchlist_entries (user_id, position);
