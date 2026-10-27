-- Repair historical account-cascade drift from live rows, including targets with zero likes.
-- Run as part of the planned maintenance window for large installations.
-- Drain writers and target row lockers before taking any repair snapshot. Ordinary reads remain available.
LOCK TABLE user_lists, comments, diary_entries, dropped_entries, picks, picks_templates, likes
    IN EXCLUSIVE MODE;

UPDATE user_lists t SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.list_id = t.id);
UPDATE comments t SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.comment_id = t.id);
UPDATE diary_entries t SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.diary_entry_id = t.id);
UPDATE dropped_entries t SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.dropped_entry_id = t.id);
UPDATE picks t SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.pick_id = t.id);
UPDATE picks_templates t SET likes_count = (SELECT COUNT(*) FROM likes l WHERE l.picks_template_id = t.id);
