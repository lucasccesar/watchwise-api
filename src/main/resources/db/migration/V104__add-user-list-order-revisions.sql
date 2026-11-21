ALTER TABLE users
    ADD COLUMN user_list_order_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE user_lists
    ADD COLUMN item_order_revision BIGINT NOT NULL DEFAULT 0;

WITH owner_max_rank AS (
    SELECT user_id, COALESCE(MAX(rank), 0) AS max_rank
    FROM user_lists
    GROUP BY user_id
), unranked_lists AS (
    SELECT lists.id,
           owner_max_rank.max_rank + ROW_NUMBER() OVER (
               PARTITION BY lists.user_id ORDER BY lists.created_at DESC, lists.id DESC
           ) AS next_rank
    FROM user_lists lists
    JOIN owner_max_rank ON owner_max_rank.user_id = lists.user_id
    WHERE lists.rank IS NULL
)
UPDATE user_lists lists
SET rank = unranked_lists.next_rank
FROM unranked_lists
WHERE lists.id = unranked_lists.id;

CREATE FUNCTION bump_user_list_order_revision_after_insert() RETURNS trigger AS $$
BEGIN
    UPDATE users
    SET user_list_order_revision = user_list_order_revision + 1
    WHERE id IN (SELECT DISTINCT user_id FROM inserted_lists);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION bump_user_list_order_revision_after_delete() RETURNS trigger AS $$
BEGIN
    UPDATE users
    SET user_list_order_revision = user_list_order_revision + 1
    WHERE id IN (SELECT DISTINCT user_id FROM deleted_lists);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION bump_user_list_order_revision_after_update() RETURNS trigger AS $$
BEGIN
    UPDATE users
    SET user_list_order_revision = user_list_order_revision + 1
    WHERE id IN (
        SELECT DISTINCT new_rows.user_id
        FROM new_list_rows new_rows
        JOIN old_list_rows old_rows ON old_rows.id = new_rows.id
        WHERE new_rows.user_id IS DISTINCT FROM old_rows.user_id
           OR new_rows.rank IS DISTINCT FROM old_rows.rank
        UNION
        SELECT DISTINCT old_rows.user_id
        FROM old_list_rows old_rows
        JOIN new_list_rows new_rows ON new_rows.id = old_rows.id
        WHERE new_rows.user_id IS DISTINCT FROM old_rows.user_id
           OR new_rows.rank IS DISTINCT FROM old_rows.rank
    );
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_lists_order_revision_insert
    AFTER INSERT ON user_lists
    REFERENCING NEW TABLE AS inserted_lists
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_user_list_order_revision_after_insert();

CREATE TRIGGER user_lists_order_revision_delete
    AFTER DELETE ON user_lists
    REFERENCING OLD TABLE AS deleted_lists
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_user_list_order_revision_after_delete();

CREATE TRIGGER user_lists_order_revision_update
    AFTER UPDATE ON user_lists
    REFERENCING OLD TABLE AS old_list_rows NEW TABLE AS new_list_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_user_list_order_revision_after_update();

CREATE FUNCTION bump_user_list_item_order_revision_after_insert() RETURNS trigger AS $$
BEGIN
    UPDATE user_lists
    SET item_order_revision = item_order_revision + 1
    WHERE id IN (SELECT DISTINCT user_list_id FROM inserted_items);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION bump_user_list_item_order_revision_after_delete() RETURNS trigger AS $$
BEGIN
    UPDATE user_lists
    SET item_order_revision = item_order_revision + 1
    WHERE id IN (SELECT DISTINCT user_list_id FROM deleted_items);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION bump_user_list_item_order_revision_after_update() RETURNS trigger AS $$
BEGIN
    UPDATE user_lists
    SET item_order_revision = item_order_revision + 1
    WHERE id IN (
        SELECT DISTINCT new_rows.user_list_id
        FROM new_item_rows new_rows
        JOIN old_item_rows old_rows ON old_rows.id = new_rows.id
        WHERE new_rows.user_list_id IS DISTINCT FROM old_rows.user_list_id
           OR new_rows.position IS DISTINCT FROM old_rows.position
        UNION
        SELECT DISTINCT old_rows.user_list_id
        FROM old_item_rows old_rows
        JOIN new_item_rows new_rows ON new_rows.id = old_rows.id
        WHERE new_rows.user_list_id IS DISTINCT FROM old_rows.user_list_id
           OR new_rows.position IS DISTINCT FROM old_rows.position
    );
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_list_items_order_revision_insert
    AFTER INSERT ON user_list_items
    REFERENCING NEW TABLE AS inserted_items
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_user_list_item_order_revision_after_insert();

CREATE TRIGGER user_list_items_order_revision_delete
    AFTER DELETE ON user_list_items
    REFERENCING OLD TABLE AS deleted_items
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_user_list_item_order_revision_after_delete();

CREATE TRIGGER user_list_items_order_revision_update
    AFTER UPDATE ON user_list_items
    REFERENCING OLD TABLE AS old_item_rows NEW TABLE AS new_item_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_user_list_item_order_revision_after_update();
