DO $$
DECLARE
    invalid_position_ids TEXT;
    invalid_top5_duplicates TEXT;
    invalid_list_item_duplicates TEXT;
    dirty_graph_components TEXT;
    mixed_scope_lists TEXT;
    owner_group RECORD;
    ordered_entry RECORD;
    expected_position INTEGER;
BEGIN
    SELECT string_agg(id::TEXT, ', ' ORDER BY id)
    INTO invalid_position_ids
    FROM (
        SELECT id FROM top5_entries WHERE position < 1 OR position > 5
        UNION ALL
        SELECT id FROM user_list_items WHERE position < 1
        ORDER BY id
        LIMIT 20
    ) invalid_entries;

    IF invalid_position_ids IS NOT NULL THEN
        RAISE EXCEPTION 'V86 refused to compact ordered entries with invalid positions; entry IDs: %',
            invalid_position_ids;
    END IF;

    SELECT string_agg(id::TEXT, ', ' ORDER BY id)
    INTO invalid_top5_duplicates
    FROM (
        SELECT id
        FROM (
            SELECT id,
                   ROW_NUMBER() OVER (
                       PARTITION BY user_id, type, position
                       ORDER BY id
                   ) AS duplicate_number
            FROM top5_entries
        ) ranked
        WHERE duplicate_number > 1
        ORDER BY id
        LIMIT 20
    ) duplicates;

    SELECT string_agg(id::TEXT, ', ' ORDER BY id)
    INTO invalid_list_item_duplicates
    FROM (
        SELECT id
        FROM (
            SELECT id,
                   ROW_NUMBER() OVER (
                       PARTITION BY user_list_id, position
                       ORDER BY id
                   ) AS duplicate_number
            FROM user_list_items
        ) ranked
        WHERE duplicate_number > 1
        ORDER BY id
        LIMIT 20
    ) duplicates;

    IF invalid_top5_duplicates IS NOT NULL OR invalid_list_item_duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'V86 refused to compact ordered entries with duplicate positions; top5 IDs: %, user-list-item IDs: %',
            COALESCE(invalid_top5_duplicates, 'none'),
            COALESCE(invalid_list_item_duplicates, 'none');
    END IF;

    SELECT string_agg(component, '; ' ORDER BY component)
    INTO dirty_graph_components
    FROM (
        SELECT DISTINCT FORMAT('%s -> %s -> %s', edge.user_list_id, edge.child_list_id, nested.child_list_id)
                AS component
        FROM user_list_items edge
        JOIN user_list_items nested ON nested.user_list_id = edge.child_list_id
        WHERE edge.child_list_id IS NOT NULL
          AND nested.child_list_id IS NOT NULL
        ORDER BY component
        LIMIT 20
    ) dirty_components;

    IF dirty_graph_components IS NOT NULL THEN
        RAISE EXCEPTION 'V86 refused to compact positions because nested-list components violate the one-level graph rule: %',
            dirty_graph_components;
    END IF;

    SELECT string_agg(user_list_id::TEXT, ', ' ORDER BY user_list_id)
    INTO mixed_scope_lists
    FROM (
        SELECT user_list_id
        FROM user_list_items
        GROUP BY user_list_id
        HAVING COUNT(content_id) > 0 AND COUNT(child_list_id) > 0
        ORDER BY user_list_id
        LIMIT 20
    ) mixed_lists;

    IF mixed_scope_lists IS NOT NULL THEN
        RAISE EXCEPTION 'V86 refused to compact positions because lists mix content and nested-list items; list IDs: %',
            mixed_scope_lists;
    END IF;

    FOR owner_group IN
        SELECT DISTINCT user_id, type
        FROM top5_entries
        ORDER BY user_id, type
    LOOP
        expected_position := 0;
        FOR ordered_entry IN
            SELECT id, position
            FROM top5_entries
            WHERE user_id = owner_group.user_id
              AND type = owner_group.type
            ORDER BY position, id
        LOOP
            expected_position := expected_position + 1;
            IF ordered_entry.position <> expected_position THEN
                UPDATE top5_entries
                SET position = expected_position
                WHERE id = ordered_entry.id;
            END IF;
        END LOOP;
    END LOOP;

    FOR owner_group IN
        SELECT DISTINCT user_list_id
        FROM user_list_items
        ORDER BY user_list_id
    LOOP
        expected_position := 0;
        FOR ordered_entry IN
            SELECT id, position
            FROM user_list_items
            WHERE user_list_id = owner_group.user_list_id
            ORDER BY position, id
        LOOP
            expected_position := expected_position + 1;
            IF ordered_entry.position <> expected_position THEN
                UPDATE user_list_items
                SET position = expected_position
                WHERE id = ordered_entry.id;
            END IF;
        END LOOP;
    END LOOP;
END $$;
