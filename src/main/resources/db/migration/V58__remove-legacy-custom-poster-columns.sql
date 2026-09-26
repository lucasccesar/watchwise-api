ALTER TABLE user_list_items DROP CONSTRAINT ck_user_list_items_poster_content_only;

ALTER TABLE diary_entries DROP COLUMN custom_poster_url;
ALTER TABLE top5_entries DROP COLUMN custom_poster_url;
ALTER TABLE user_list_items DROP COLUMN custom_poster_url;
