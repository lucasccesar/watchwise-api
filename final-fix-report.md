# Daily Games v2 final fix wave

Date: 2026-09-30

## Implemented fixes

- Activated the Daily Games generation and current-day attempt-details cleanup defaults in the production profile, including the bounded filmography series-detail lookup default.
- Added production scheduler startup coverage for both Daily Games jobs.
- Replaced episode dropdown routes with the approved series/season path contract and synchronized controller tests and ignored context docs/OpenAPI.
- Made selected-series and selected-season TMDB `NotFound` results return `NotFoundException`/404 while preserving valid-empty lists and 502 for unavailable TMDB responses.
- Routed `/games/{gameType}/today` through the service-injected `Clock`.
- Persisted candidate title, image URL, and date from the validating TMDB response, including metadata-preserving person cache seeding; invalid and unavailable candidates do not append attempt details.
- Added a per-generation cached series-detail lookup budget with all-role fallback and exact denominators when lookups are available.
- Updated stale episode-generation/legacy-row wording in ignored context docs and synchronized business rules and progress.

## Verification

- `mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameControllerIntegrationTest,DailyGameSearchServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyChallengeResponseAssemblerTest,DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameRankingServiceImplTest,DailyGameGenerationJobTest,DailyGameRepositoryTest,DailyGameAttemptDetailsCleanupServiceImplTest,DailyGameAttemptDetailsCleanupJobTest,DailyGameInfoComparisonServiceTest,DailyGameFilmographyServiceTest,DailyGameProductionConfigurationTest,TmdbClientCachingTest" -DfailIfNoTests=false` — exit 0; 255 tests, 0 failures, 0 errors.
- `mvnw.cmd test` — exit 0; 3,210 tests, 0 failures, 0 errors.
- `mvnw.cmd clean package` — exit 0; 3,273 tests, 0 failures, 0 errors; Flyway/Hibernate validation passed and the Spring Boot jar was repackaged.
- Route/documentation consistency search found no stale `/search/seasons` or `/search/episodes` references in the scoped source/docs.

No push was performed. The ignored `docs/context` updates remain worktree-only per repository instructions.
