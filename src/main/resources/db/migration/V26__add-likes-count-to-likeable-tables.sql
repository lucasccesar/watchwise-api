ALTER TABLE comments ADD COLUMN likes_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE diary_entries ADD COLUMN likes_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE user_lists ADD COLUMN likes_count INTEGER NOT NULL DEFAULT 0;

UPDATE comments c SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.comment_id = c.id);
UPDATE diary_entries d SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.diary_entry_id = d.id);
UPDATE user_lists u SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.list_id = u.id);
