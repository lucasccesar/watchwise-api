# Task 8 — Remove legacy poster columns and verify the complete behavior

## Implemented

- Added `V58__remove-legacy-custom-poster-columns.sql` after V57.
- Removed `custom_poster_url` from `diary_entries`, `top5_entries`, and `user_list_items`.
- Removed `ck_user_list_items_poster_content_only` before dropping the list-item column.
- Added a Testcontainers migration test that migrates V56 → V57 with valid and invalid legacy rows,
  verifies the canonical backfill, then applies V58 and verifies the legacy schema is gone.
- Added the missing canonical-poster mock to `UserListServiceImplTest`.

## Verification

- RED migration test: failed at the expected legacy-column assertion before V58.
- Migration test: 1 test, 0 failures, 0 errors.
- Full `mvnw.cmd test` before the fixture correction: 2,974 tests, 0 failures, 9 errors.
- Of those 9 errors, 7 were `PersonControllerIntegrationTest` environment failures and 2 were the
  missing `UserContentPosterService` fixture setup.
- After the fixture correction, `UserListServiceImplTest`: 94 tests, 0 failures, 0 errors.
- After the fixture correction, the Task 8 targeted suite: 489 tests, 0 failures, 0 errors.
- The full suite was not rerun after the fixture correction.

## Concerns

The 7 `PersonControllerIntegrationTest` errors could not load the context because the default `dev`
profile points to unavailable `localhost:15432`; the same class reproduces this failure in isolation.
The 2 pre-correction fixture errors were caused by missing `UserContentPosterService` setup and are fixed
by this task. The alternate `test` profile starts PostgreSQL and applies V58, but is incomplete for this
controller test because it lacks unrelated application properties.

The pre-existing dirty/deleted files and documentation outside this report were preserved.

Commit: `fix(content-poster): verify legacy poster migration`
