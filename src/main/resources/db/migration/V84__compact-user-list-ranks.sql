DO $$
DECLARE
    list_row RECORD;
    current_user_id UUID;
    expected_rank INTEGER;
BEGIN
    IF EXISTS (SELECT 1 FROM user_lists WHERE rank IS NOT NULL AND rank <= 0) THEN
        RAISE EXCEPTION 'Cannot compact user list ranks while a non-positive rank exists';
    END IF;

    FOR list_row IN
        SELECT id, user_id, rank
        FROM user_lists
        WHERE rank IS NOT NULL
        ORDER BY user_id, rank, id
    LOOP
        IF current_user_id IS DISTINCT FROM list_row.user_id THEN
            current_user_id := list_row.user_id;
            expected_rank := 1;
        END IF;

        IF list_row.rank <> expected_rank THEN
            UPDATE user_lists
            SET rank = expected_rank
            WHERE id = list_row.id;
        END IF;

        expected_rank := expected_rank + 1;
    END LOOP;
END $$;
