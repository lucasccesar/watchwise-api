# TMDB content images endpoint design

## Goal

Expose the TMDB poster and backdrop collections for movies and series through authenticated Watchwise endpoints. Keep the TMDB credential on the backend and return image URLs that the client can render directly.

## API contract

- `GET /contents/selection/{type}/{tmdbId}/posters`
- `GET /contents/selection/{type}/{tmdbId}/backdrops`

`type` is `MOVIE` or `SERIES`. Each route returns only the requested image list as an array. Each image item contains a complete TMDB URL, width, height, aspect ratio, language, vote average, and vote count. Poster URLs use the existing `w500` size and backdrop URLs use `w1280`.

Both routes return all language variants in the order supplied by TMDB. They do not expose logos or add a language filter. Missing image arrays become empty arrays; entries without a usable file path are omitted.

## Request behavior

- Both routes require authentication and use the existing per-user content-selection rate limit.
- IDs must be positive numeric TMDB identifiers.
- A missing movie or series returns `404`; invalid IDs return `400`; rate-limited requests return `429`; TMDB outages return `502` with `ApiError`.
- Responses are read-only and do not create `Content` rows.
- `TmdbClient` owns the movie and TV upstream calls and caches the combined TMDB image response by media type and TMDB ID. The content service returns the requested list and maps upstream image records to the image DTO with complete URLs.

## Architecture

Extend the existing content selection flow with the two explicit image-kind routes and a shared image item contract. Keep upstream JSON records in `common/tmdb`, use the existing `TmdbImageUrlBuilder`, and follow the current `TmdbLookupResult` handling for found, missing, and unavailable responses. No database migration is needed.

## Alternatives considered

1. One `/images` route returning both collections would avoid duplicate route declarations, but would require clients to download a collection they may not need.
2. Two routes separated by image kind were selected. The `MOVIE`/`SERIES` path type is shared, and each response contains only posters or only backdrops. Both routes reuse the same cached upstream image response.

## Review checklist

- Posters and backdrops have distinct routes, and logos are excluded.
- All language variants remain available, including language-neutral images.
- Cache keys distinguish movies from series that happen to share the same numeric ID.
- Invalid IDs cannot become arbitrary upstream paths.
- No local media data is persisted.
