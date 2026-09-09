# Search Service Tests Design

## Goal

Complete the service-level test coverage required by phase 8, item 6, without moving controller parameter validation into `SearchServiceImpl`.

## Scope

Extend `SearchServiceImplTest` to cover every supported search type, mixed TMDB multi-search mapping, card mapping, page normalization and the limit of 20, escaped local search terms, list visibility delegation, batched list-card enrichment, empty results, local-only searches without TMDB calls, and TMDB unavailability.

The minimum query length is an HTTP-input concern. The future `SearchController` will validate a request-parameter DTO with `@Valid`, `@NotBlank`, and `@Size(min = 3)`. The service tests will therefore verify trimming and delegation, but will not add a duplicate minimum-length rule to `SearchServiceImpl`.

## Test boundaries

- Service tests mock repositories and external clients, asserting the service's orchestration and DTO mapping.
- List visibility remains the responsibility of `UserListRepository.findVisibleByNameContainingIgnoreCase`; service tests assert that the authenticated viewer ID is passed through.
- Preview items and nested-list counts are fetched through the existing batch methods once per result page, including empty pages.
- Local `LIST` and `USER` searches must never interact with `TmdbClient`.
- External `MOVIE`, `SERIES`, `PERSON`, and omitted-type searches convert an unavailable TMDB result into `TmdbUnavailableException`.

## Verification

Run `SearchServiceImplTest` first, then the relevant pagination tests and the full Maven test suite when the environment permits dependency resolution. Do not alter the existing uncommitted implementation changes outside the files required by this coverage.
