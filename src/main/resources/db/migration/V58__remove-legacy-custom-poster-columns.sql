CREATE TABLE user_content_poster_conflict_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    discarded_source VARCHAR(32) NOT NULL,
    discarded_source_id UUID NOT NULL,
    discarded_custom_poster_url VARCHAR(2048) NOT NULL,
    discarded_created_at TIMESTAMP NOT NULL,
    discarded_updated_at TIMESTAMP NOT NULL,
    selected_source VARCHAR(32) NOT NULL,
    selected_source_id UUID NOT NULL,
    selected_custom_poster_url VARCHAR(2048) NOT NULL,
    selected_created_at TIMESTAMP NOT NULL,
    selected_updated_at TIMESTAMP NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

WITH legacy_posters AS (
    SELECT d.id AS source_id,
           d.user_id,
           d.content_id,
           d.custom_poster_url,
           d.created_at,
           d.updated_at,
           'DIARY_ENTRY' AS source,
           1 AS source_order
    FROM diary_entries d
    WHERE d.custom_poster_url LIKE 'https://image.tmdb.org/t/p/w342/%'
      AND length(d.custom_poster_url) > length('https://image.tmdb.org/t/p/w342/')

    UNION ALL

    SELECT t.id AS source_id,
           t.user_id,
           t.content_id,
           t.custom_poster_url,
           t.created_at,
           t.updated_at,
           'TOP5_ENTRY' AS source,
           2 AS source_order
    FROM top5_entries t
    WHERE t.custom_poster_url LIKE 'https://image.tmdb.org/t/p/w342/%'
      AND length(t.custom_poster_url) > length('https://image.tmdb.org/t/p/w342/')

    UNION ALL

    SELECT i.id AS source_id,
           l.user_id,
           i.content_id,
           i.custom_poster_url,
           i.created_at,
           i.updated_at,
           'USER_LIST_ITEM' AS source,
           3 AS source_order
    FROM user_list_items i
    JOIN user_lists l ON l.id = i.user_list_id
    WHERE i.content_id IS NOT NULL
      AND i.custom_poster_url LIKE 'https://image.tmdb.org/t/p/w342/%'
      AND length(i.custom_poster_url) > length('https://image.tmdb.org/t/p/w342/')
), ranked_posters AS (
    SELECT source_id,
           user_id,
           content_id,
           custom_poster_url,
           created_at,
           updated_at,
           source,
           ROW_NUMBER() OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS row_number,
           COUNT(*) OVER (PARTITION BY user_id, content_id) AS candidate_count,
           FIRST_VALUE(source) OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS selected_source,
           FIRST_VALUE(source_id) OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS selected_source_id,
           FIRST_VALUE(custom_poster_url) OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS selected_custom_poster_url,
           FIRST_VALUE(created_at) OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS selected_created_at,
           FIRST_VALUE(updated_at) OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS selected_updated_at
    FROM legacy_posters
)
INSERT INTO user_content_poster_conflict_audit (
    user_id, content_id,
    discarded_source, discarded_source_id, discarded_custom_poster_url,
    discarded_created_at, discarded_updated_at,
    selected_source, selected_source_id, selected_custom_poster_url,
    selected_created_at, selected_updated_at
)
SELECT user_id, content_id,
       source, source_id, custom_poster_url,
       created_at, updated_at,
       selected_source, selected_source_id, selected_custom_poster_url,
       selected_created_at, selected_updated_at
FROM ranked_posters
WHERE candidate_count > 1
  AND row_number > 1;

ALTER TABLE user_list_items DROP CONSTRAINT ck_user_list_items_poster_content_only;

ALTER TABLE diary_entries DROP COLUMN custom_poster_url;
ALTER TABLE top5_entries DROP COLUMN custom_poster_url;
ALTER TABLE user_list_items DROP COLUMN custom_poster_url;
