ALTER TABLE user_list_items ADD CONSTRAINT ck_user_list_items_poster_content_only
    CHECK (child_list_id IS NULL OR custom_poster_url IS NULL);
