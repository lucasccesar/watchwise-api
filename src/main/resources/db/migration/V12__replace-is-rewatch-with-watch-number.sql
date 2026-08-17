ALTER TABLE diary_entries ADD COLUMN watch_number INTEGER;

UPDATE diary_entries de
SET watch_number = sub.rn
FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id, content_id ORDER BY created_at) AS rn
    FROM diary_entries
) sub
WHERE de.id = sub.id;

ALTER TABLE diary_entries ALTER COLUMN watch_number SET NOT NULL;

ALTER TABLE diary_entries ADD CONSTRAINT uq_diary_entries_user_content_watch_number
    UNIQUE (user_id, content_id, watch_number);

ALTER TABLE diary_entries DROP COLUMN is_rewatch;
