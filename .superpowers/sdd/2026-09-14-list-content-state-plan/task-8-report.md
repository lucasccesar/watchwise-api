# Task 8 verification report

Branch: `codex/list-content-state`
HEAD: `f189e33`
Merge base: `67222f2`

## Verification

- `mvn.cmd "-Dmaven.repo.local=D:\Users\Lucas C\Documentos\codigos\watchwise-api\.m2-local" -o test-compile` passed with `BUILD SUCCESS`.
- The full offline test suite ran 1,650 tests with 0 failures, 42 errors and 0 skipped. All 42 errors occurred during Testcontainers startup because `\\.\pipe\docker_engine` was inaccessible; the XML reports contain no assertion failures.
- `clean package -DskipTests` could not resolve `maven-clean-plugin:3.5.0` from the offline cache.
- `package -DskipTests` could not resolve `maven-jar-plugin:3.5.0` from the offline cache.
- OpenAPI YAML parsing passed with PyYAML.
- Static audit found no list-service dependency on `CalendarService`, no episode-per-item reader call in the list state path, no migration or Content entity change, and no whitespace errors in the tracked branch diff.

The final review must distinguish the feature commits from the two baseline synchronization commits required because the original checkout contained uncommitted `mvnw.cmd`, pagination, and Search contracts needed for compilation. Task 8 does not modify code or documentation.

## Final review fix wave

- Root cause: after `CalendarScheduleProviderImpl` was refactored to use `ContentScheduleReader`, its season and series refresh paths inherited the reader's general `getSeasonFullDetails` lookup. This bypassed the bounded calendar schedule cache and its invalidation boundary.
- Fix: `ContentScheduleReader` now exposes calendar-specific season and series reads. Both preserve the shared schedule construction while selecting `getCalendarSeasonDetails`; content/list reads still select `getSeasonFullDetails`. `CalendarScheduleProviderImpl` calls only the calendar-specific reads.
- Regression coverage: `TmdbClientCachingTest` drives the calendar provider through a real reader and client. It proves that invalidating the calendar season cache causes a second remote season read, repopulates that cache, and leaves the general season-details cache empty. The test was first run red against the pre-fix endpoint/cache path, then green after the fix.
- Review coverage: removed the duplicate `ContentScheduleEpisode` import; added the independent `ContentScheduleLookup.NotFound` unknown-state service-path assertion; changed the repository fixture to a Marina-only `1399` season 2 episode coordinate.
- Focused non-Docker verification passed: `TmdbClientCachingTest`, `ContentScheduleReaderImplTest`, `CalendarScheduleProviderTest`, `UserListContentStateServiceImplTest`, `UserListItemServiceImplTest`, `UserListServiceImplTest`, and `UserListItemMapperTest` ran 239 tests with 0 failures and 0 errors.
- `DiaryEntryRepositoryTest` could not execute because Testcontainers reported no valid Docker environment. It produced 0 assertion failures and 1 infrastructure startup error. The full suite was not rerun in this final fix wave.
- Final self-review confirmed calendar paths do not call the general season cache, content/list paths retain it, the cache regression checks both invalidation and cache isolation, and `git diff --check` is clean.
