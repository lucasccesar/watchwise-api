# Daily Game Feed Sharing Design

**Date:** 2026-09-29

## Goal

Allow the user to manually enable a toggle while playing a daily game so the
result of that modality is shared to followers' feeds immediately when the
game finishes, whether the result is `COMPLETED` or `FAILED`.

Sharing is per modality, permanent, and never happens automatically when the
toggle was not enabled.

## Scope

The feature covers the current daily-game screen and the current day's game.
The client sends the current toggle state with each attempt. Sharing an old
history result after the game has already finished is outside this feature.

## Existing architecture constraints

- Daily-game results already have one row per user and challenge in
  `user_daily_game_results`.
- `DailyGameServiceImpl.submitAttempt` locks that result before changing its
  state and runs inside a transaction.
- The feed is pull-based: `FeedServiceImpl` merges ordered candidates from
  source repositories at read time.
- Feed visibility is limited to users followed with `FollowStatus.ACCEPTED`.
- Feed cursor ordering uses `createdAt` and the source row `id`.
- Feed social interactions remain limited to diary events; daily-game events
  do not receive likes or comments.

## Design

### Daily-game result state

Extend `UserDailyGameResult` with:

- `shareOnCompletion`, a non-null boolean defaulting to `false`. It records the
  current toggle preference while the result is `IN_PROGRESS`.
- `sharedAt`, a nullable timestamp. It is set exactly once when a terminal
  result is completed with `shareOnCompletion = true`.

The migration must add both columns with safe defaults for existing rows and a
partial index supporting feed reads for rows where `shared_at IS NOT NULL`.

The entity must expose operations that allow changing the pending preference
and marking the result as shared, but must not expose an operation that clears
`sharedAt`. The database column remains nullable because unshared results are
valid.

### Attempt request and response

Add an optional `shareOnCompletion` boolean to
`DailyGameAttemptRequest`:

- `true`: enable the toggle for the current result;
- `false`: disable the toggle while the result has not been shared;
- `null` or omitted: preserve the stored preference for compatibility with
  older clients.

After the candidate is validated, `submitAttempt` updates the stored
preference when the request supplied one. If the attempt makes the result
terminal and the preference is enabled, it sets `sharedAt` to the transaction's
current clock time. If the result remains open, it never sets `sharedAt`.

The daily-game state and attempt response expose:

- `shareOnCompletion`, so the screen can restore the toggle state;
- `sharedToFeed`, derived from `sharedAt != null`, so the screen can show that
  publication already happened.

History responses expose `sharedToFeed`; the pending preference is not needed
for a finished historical item.

The existing lock and terminal-state guard make the operation idempotent in
practice: a second terminal attempt is rejected, and the same result row can
only receive one `sharedAt` value.

### Feed event

Add `DAILY_GAME_RESULT` to `FeedEventType` and a nullable
`dailyGameResult` block to `FeedItemDTO`.

The block contains the frozen, terminal result needed by the client:

- challenge date;
- game type and target kind;
- maximum attempts and attempts used;
- status and score;
- terminal answer snapshot, including its title, identifiers, and image URL.

The daily-game preview is assembled from the existing `DailyChallenge`
snapshot, so rendering the feed does not require a new TMDB request and does
not expose an answer before the result is terminal.

`FeedServiceImpl` adds `UserDailyGameResult` as a sixth source. Its repository
query filters by followed user IDs and `sharedAt IS NOT NULL`, and applies the
same keyset condition using `sharedAt` and the result ID. The mapper sets:

- `id` to the result ID;
- `user` to the result owner preview;
- `dailyGameResult` to the preview block;
- `createdAt` to `sharedAt`;
- unrelated content, score/comment, likes, watched-with, pick, and template
  fields to `null`.

The service merges this source with the five existing sources, fetches one
extra row per source for `hasNext`, and keeps the existing cursor behavior.

### Authorization and visibility

The attempt endpoint continues to use the authenticated user ID and CSRF
protection. A user can change or create a share only on their own result,
because the result lookup is scoped by the authenticated user and challenge.

Followers see the event only when their follow relationship is
`ACCEPTED`, matching the existing feed rule. A shared failed result is
intentionally visible with its answer because the product requirement allows
sharing either terminal outcome.

### Error behavior

- Invalid candidates do not consume an attempt and do not change the pending
  sharing preference.
- `IN_PROGRESS` results cannot create feed events.
- Finished results still reject additional attempts with the existing conflict
  response.
- The new migration and repository query must preserve the existing unique
  `(user_id, daily_challenge_id)` constraint.
- Any new binding failures must continue through `GlobalExceptionHandler` in
  the project's `ApiError` format.

## Testing strategy

Service tests will cover terminal success and failure with sharing, open games
with and without a pending preference, preference changes across attempts,
invalid candidates leaving state untouched, and permanent single publication.

Repository/integration tests will cover the new columns, default values, and
feed candidate filtering and cursor ordering.

Controller tests will verify request binding, response fields, CSRF behavior,
and the updated OpenAPI-shaped payloads.

Feed tests will verify merging and ordering of six sources, `hasNext` when the
daily-game source has more rows, follower visibility, and the null social
fields for `DAILY_GAME_RESULT`.

## Documentation updates

The implementation must update, in the same change:

- `docs/context/openapi.yaml`;
- `docs/context/database-schema.md` and `docs/context/database-schema.html`;
- `docs/context/business-rules-summary.md`;
- `docs/context/progress.md`.

No endpoint for removing a share will be added. No separate share table or
materialized feed table will be introduced.
