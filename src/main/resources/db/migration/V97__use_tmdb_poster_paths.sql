ALTER TABLE user_content_posters
    DROP CONSTRAINT ck_user_content_posters_custom_poster_url;

DELETE FROM user_content_posters
WHERE custom_poster_url LIKE 'https://image.tmdb.org/t/p/w342/%'
  AND regexp_replace(
          substring(custom_poster_url from char_length('https://image.tmdb.org/t/p/w342/') + 1),
          '^/+',
          ''
      ) = '';

UPDATE user_content_posters
SET custom_poster_url = '/' || regexp_replace(
        substring(custom_poster_url from char_length('https://image.tmdb.org/t/p/w342/') + 1),
        '^/+',
        ''
    )
WHERE custom_poster_url LIKE 'https://image.tmdb.org/t/p/w342/%';

ALTER TABLE user_content_posters
    ADD CONSTRAINT ck_user_content_posters_custom_poster_url CHECK (
        custom_poster_url LIKE '/%'
        AND custom_poster_url NOT LIKE '//%'
        AND length(custom_poster_url) > 1
    );
