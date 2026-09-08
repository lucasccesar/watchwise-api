# Search TMDB Proxy Design

## Goal

Extend the existing TMDB client with typed movie, series, person, and multi-search operations required by phase 8, item 4. Search responses remain transient and are never persisted.

## Scope

This change adds only the external TMDB proxy layer. It does not implement `SearchService`, local user or list aggregation, response-card mapping, or `/search`. Local-only `LIST` and `USER` filters therefore have no TMDB client operation; the future service will skip the external client for those filters.

## Models and client interface

A generic `TmdbSearchPage<T>` represents TMDB pagination metadata and its ordered `results` array. Dedicated result records represent movie, series, person, and multi-search payloads. The multi-search result retains `media_type` and the media-specific name, image, and date fields so the future service can separate people from content while preserving the relative order of movies and series.

`TmdbClient` exposes four typed methods:

- `searchMovies(query, language, page)`
- `searchTv(query, language, page)`
- `searchPeople(query, language, page)`
- `searchMulti(query, language, page)`

Each operation sends `query`, `language`, `page`, and `include_adult=false`. It returns `TmdbLookupResult<TmdbSearchPage<...>>`, retaining the existing distinction between found, unavailable, and not found results.

## Cache and resilience

Search uses dedicated Caffeine caches rather than the detail caches. Every cache key contains the normalized query, search type, language, and page. Query normalization trims surrounding whitespace and lowercases with `Locale.ROOT`, making equivalent searches share an entry without changing the value sent to TMDB on a cache miss.

The cache uses a short configurable TTL measured in minutes and a configurable maximum size. The size bound prevents arbitrary query strings from growing the in-memory cache without limit. Caffeine's atomic loader collapses concurrent requests for the same uncached key into one external call.

The client reuses the existing single-retry behavior. Successful and confirmed not-found results may be cached; unavailable results are not cached, allowing the next request to retry immediately. No stale fallback or database persistence is introduced.

## Configuration

Development and production property templates receive settings for the search-cache TTL and maximum size. Existing TMDB authentication, base URL, and timeout configuration remain unchanged.

## Testing

Tests are written first and must fail because the new API does not yet exist. Client tests cover each endpoint's URL and response parsing, including mixed multi-search ordering. Cache tests cover normalized-query hits, key isolation by type, language, and page, concurrent request collapsing, and retry after an unavailable result. Existing TMDB client and full Maven tests must remain green.

## Repository cleanup

After the functional change is verified, `.agents`, `.claude`, `.codex`, `.superpowers`, `docs`, `CLAUDE.md`, and `AGENTS.md` are added to `.gitignore` and removed only from the Git index. Their local files remain intact. `.mvn`, `mvnw`, and `mvnw.cmd` remain tracked because they are required for reproducible Maven builds.

The functional implementation and repository cleanup are committed separately with Conventional Commit messages and no attribution trailers.
