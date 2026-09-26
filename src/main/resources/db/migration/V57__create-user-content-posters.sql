CREATE TABLE user_content_posters (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    custom_poster_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_content_posters_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_content_posters_content FOREIGN KEY (content_id) REFERENCES contents (id) ON DELETE CASCADE,
    CONSTRAINT uq_user_content_posters_user_id_content_id UNIQUE (user_id, content_id),
    CONSTRAINT ck_user_content_posters_custom_poster_url CHECK (
        custom_poster_url LIKE 'https://image.tmdb.org/t/p/w342/%'
        AND length(custom_poster_url) > length('https://image.tmdb.org/t/p/w342/')
    )
);

CREATE INDEX idx_user_content_posters_user_id ON user_content_posters (user_id);
CREATE INDEX idx_user_content_posters_content_id ON user_content_posters (content_id);

WITH legacy_posters AS (
    SELECT d.id AS source_id,
           d.user_id,
           d.content_id,
           d.custom_poster_url,
           d.created_at,
           d.updated_at,
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
           ROW_NUMBER() OVER (
               PARTITION BY user_id, content_id
               ORDER BY updated_at DESC, source_order ASC, source_id ASC
           ) AS row_number
    FROM legacy_posters
)
INSERT INTO user_content_posters (
    id, user_id, content_id, custom_poster_url, created_at, updated_at
)
SELECT gen_random_uuid(),
       user_id,
       content_id,
       custom_poster_url,
       created_at,
       updated_at
FROM ranked_posters
WHERE row_number = 1;
