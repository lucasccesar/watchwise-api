# TMDB Content Image Endpoints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add separate authenticated poster and backdrop routes for TMDB movies and series.

**Architecture:** `TmdbClient` fetches the combined TMDB image response for a movie or series and caches it using a media-type-qualified key. A content service selects the requested list, converts image paths into Watchwise URLs, and exposes the same image item DTO from both routes. The selection controller applies the existing per-user selection rate limit.

**Tech Stack:** Java 21, Spring Boot, Spring `RestClient`, Jackson records, Caffeine, Maven Wrapper, OpenAPI YAML.

## Global Constraints

- `type` is `MOVIE` or `SERIES`.
- Each route returns only the requested image list as an array.
- Each image item contains a complete TMDB URL, width, height, aspect ratio, language, vote average, and vote count.
- Poster URLs use the existing `w500` size and backdrop URLs use `w1280`.
- Both routes return all language variants in the order supplied by TMDB.
- Both routes require authentication and use the existing per-user content-selection rate limit.
- IDs must be positive numeric TMDB identifiers.
- Responses are read-only and do not create `Content` rows.
- No database migration is needed.
- Do not add or run tests under the current session constraints; use compile-only verification.
- Documentation files remain worktree-only and are not included in the code commit.

---

## File Map

- `common/tmdb/TmdbContentImage.java`: one upstream image record, including TMDB snake-case JSON mappings.
- `common/tmdb/TmdbContentImages.java`: upstream posters and backdrops collection.
- `common/tmdb/TmdbClient.java`: movie/TV image requests and shared cached response.
- `common/tmdb/TmdbCacheConfig.java`: bounded cache using the existing details-cache policy.
- `common/cache/CacheBudgetAllocator.java`: register the new cache in weighted memory allocation.
- `content/dto/ContentImageDTO.java`: public image item with a complete URL and supported metadata.
- `content/service/ContentImagesService.java`: poster and backdrop list operations.
- `content/service/impl/ContentImagesServiceImpl.java`: ID validation, upstream result handling, and URL mapping.
- `content/controller/ContentSelectionController.java`: two route handlers and existing selection throttling.
- `docs/context/openapi.yaml`: both paths, parameters, response schema, and errors.
- `docs/context/business-rules.md`: document the new TMDB image proxy behavior next to the existing content-details proxy rule.
- `docs/context/progress.md`: append the shipped feature to the existing `2026-11-02` entry.

## Task 1: Implement and document the TMDB poster/backdrop routes

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbContentImage.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbContentImages.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCacheConfig.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/common/cache/CacheBudgetAllocator.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/dto/ContentImageDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/ContentImagesService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/content/service/impl/ContentImagesServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/content/controller/ContentSelectionController.java`
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- `TmdbClient.getMovieImages(String tmdbId)` returns `TmdbLookupResult<TmdbContentImages>` and requests `/movie/{id}/images`.
- `TmdbClient.getTvImages(String tmdbId)` returns `TmdbLookupResult<TmdbContentImages>` and requests `/tv/{id}/images`.
- Cache keys use `movie|{tmdbId}` and `tv|{tmdbId}` so equal numeric IDs from different media types cannot collide.
- `ContentImagesService.listPosters(MovieOrSeriesType type, String tmdbId)` returns `List<ContentImageDTO>`.
- `ContentImagesService.listBackdrops(MovieOrSeriesType type, String tmdbId)` returns `List<ContentImageDTO>`.
- `GET /contents/selection/{type}/{tmdbId}/posters` and `/backdrops` each return `List<ContentImageDTO>`.
- `ContentImageDTO` fields are `url`, `width`, `height`, `aspectRatio`, `language`, `voteAverage`, and `voteCount`.

- [ ] Add `TmdbContentImage` fields `filePath`, `width`, `height`, `aspectRatio`, `language`, `voteAverage`, and `voteCount`, mapped from TMDB's `file_path`, `aspect_ratio`, `iso_639_1`, `vote_average`, and `vote_count` fields.
- [ ] Add `TmdbContentImages` with `List<TmdbContentImage> posters` and `List<TmdbContentImage> backdrops`; ignore unknown JSON fields such as the root ID and logos.
- [ ] Add a `tmdbContentImagesCache` bean in `TmdbCacheConfig` using `app.tmdb.details-cache-ttl-hours` and `app.tmdb.details-cache-maximum-size`.
- [ ] Add `tmdbContentImagesCache` to `CacheBudgetAllocator.METADATA_CACHES` so weighted cache mode allocates a bounded share for it.
- [ ] Add setter injection for that cache in `TmdbClient` so existing constructor call sites remain compatible. Implement both upstream lookups through `cachedLookup` and `callWithRetry`, without a language filter, using `movie|{tmdbId}` and `tv|{tmdbId}` keys.
- [ ] Add `ContentImageDTO` and `ContentImagesService` with `listPosters` and `listBackdrops` methods.
- [ ] In `ContentImagesServiceImpl`, validate `tmdbId` with the same positive-numeric rule used by content selection (`[1-9]\\d*`), dispatch `MOVIE` to `getMovieImages` and `SERIES` to `getTvImages`, and translate `NotFound` to `NotFoundException` and `Unavailable` or null to `TmdbUnavailableException`.
- [ ] Return empty lists when TMDB omits a collection; omit null image entries and entries without a usable `filePath`. Map poster paths with `TmdbImageUrlBuilder.posterUrl` and backdrop paths with `TmdbImageUrlBuilder.backdropUrl`, preserving TMDB ordering and metadata.
- [ ] Add `listPosters` and `listBackdrops` handlers to `ContentSelectionController`; each obtains the authenticated viewer ID and calls the existing `checkRateLimit` before the service call.
- [ ] Add the two OpenAPI paths with `type` enum `[MOVIE, SERIES]`, positive numeric `tmdbId`, array responses referencing `ContentImageDTO`, and `400`, `401`, `404`, `429`, and `502` responses. Add the `ContentImageDTO` schema with URI `url`, integer dimensions and vote count, numeric aspect ratio and vote average, and nullable language.
- [ ] Extend the existing TMDB proxy business rule with the new poster/backdrop routes, shared combined-response cache, language behavior, and the fact that the routes do not create `Content` rows.
- [ ] Append the completed routes and behavior under the existing `## 2026-11-02` progress heading; do not add future work.
- [ ] Run `.\\mvnw.cmd -DskipTests package` from the repository root to compile/package without running tests.
- [ ] Review `git diff --check` and `git status`; preserve pre-existing documentation edits, and stage only the new/modified Java source files for the code commit.
- [ ] Commit the implementation with `feat(content): add TMDB poster and backdrop routes` and confirm the commit has no `Co-Authored-By` trailer or other self-attribution.

## Plan self-review

- **Spec coverage:** separate poster/backdrop routes, shared movie/series type, metadata, URL sizes, all languages, empty/missing image handling, auth, rate limit, errors, cache weighting, and no persistence are covered by Task 1.
- **Placeholder scan:** the plan names concrete files, method signatures, fields, cache keys, response behavior, and commands; no TODO/TBD steps remain.
- **Type consistency:** TMDB client methods and the content service share `TmdbContentImages`; controller methods return `List<ContentImageDTO>`; OpenAPI describes the same array type.
