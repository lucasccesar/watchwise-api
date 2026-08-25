CREATE INDEX idx_diary_entries_user_id_created_at ON diary_entries (user_id, created_at DESC);

CREATE INDEX idx_comments_content_id_created_at ON comments (content_id, created_at);
CREATE INDEX idx_comments_diary_entry_id_created_at ON comments (diary_entry_id, created_at);
CREATE INDEX idx_comments_list_id_created_at ON comments (list_id, created_at);
