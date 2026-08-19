CREATE TABLE user_list_items (
    id UUID PRIMARY KEY,
    user_list_id UUID NOT NULL,
    content_id UUID,
    child_list_id UUID,
    position INTEGER NOT NULL,
    description VARCHAR(400),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_list_items_user_list FOREIGN KEY (user_list_id) REFERENCES user_lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_list_items_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_list_items_child_list FOREIGN KEY (child_list_id) REFERENCES user_lists (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_list_items_user_list_id_position UNIQUE (user_list_id, position),
    CONSTRAINT uq_user_list_items_user_list_id_content_id UNIQUE (user_list_id, content_id),
    CONSTRAINT uq_user_list_items_user_list_id_child_list_id UNIQUE (user_list_id, child_list_id),
    CONSTRAINT ck_user_list_items_target CHECK (
        (content_id IS NOT NULL AND child_list_id IS NULL) OR
        (content_id IS NULL AND child_list_id IS NOT NULL)
    ),
    CONSTRAINT ck_user_list_items_no_self_reference CHECK (child_list_id IS NULL OR child_list_id <> user_list_id),
    CONSTRAINT ck_user_list_items_position CHECK (position >= 1)
);

CREATE INDEX idx_user_list_items_user_list_id ON user_list_items (user_list_id);
CREATE INDEX idx_user_list_items_content_id ON user_list_items (content_id);
CREATE INDEX idx_user_list_items_child_list_id ON user_list_items (child_list_id);
