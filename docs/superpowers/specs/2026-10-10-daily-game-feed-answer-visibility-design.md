# Daily Game Feed Answer Visibility Design

**Date:** 2026-10-10

## Problem

`DAILY_GAME_RESULT` feed items currently include the frozen challenge answer for every accepted follower when the author shares a terminal result. A follower who has not completed that challenge can therefore receive a spoiler.

## Goal

Keep the shared daily-game result visible in the follower's feed while revealing its `answer` only when the authenticated viewer has a terminal result for the same daily challenge.

## Behavior

- The feed item remains visible to accepted followers when the author has `sharedAt`.
- The author's `status`, `attemptsUsed`, and `score` remain visible.
- `answer` is returned when the viewer's result for the same `DailyChallenge` is `COMPLETED` or `FAILED`.
- `answer` is `null` when the viewer has no result, has `IN_PROGRESS`, or has any non-terminal state.
- The author sees the answer because the author's shared result is terminal by construction.
- The rule applies to both public feed service read paths so an internal caller cannot bypass the viewer-specific redaction.
- No attempt details, new persistence column, or change to the author's sharing behavior is introduced.

## Chosen approach

`FeedServiceImpl` will collect the `DailyChallenge` IDs from the selected daily-game candidates and load the authenticated viewer's matching `UserDailyGameResult` rows in one batch using the existing repository method. A small status-based lookup will determine which challenge IDs are terminal for the viewer. The daily-game feed mapper will receive that visibility decision and pass either the frozen answer or `null` into `DailyGameResultPreviewDTO`.

This keeps database access batched, avoids one query per feed item, and leaves the source result immutable. A query-level projection was rejected because it would couple the feed's visibility rule to a large source query. Per-item lookups were rejected because they create an N+1 query pattern.

## Data flow

1. Select and merge feed candidates as today, including only shared daily-game results.
2. Collect the selected daily-game challenge IDs.
3. Batch-load the viewer's results for those challenge IDs.
4. Mark only `COMPLETED` and `FAILED` viewer results as answer-visible.
5. Build each `DailyGameResultPreviewDTO` with the source result metadata and a conditional answer.

When no daily-game item is selected, the batch lookup is skipped.

## Testing

- A viewer with no result for the challenge receives the feed item with `answer: null`.
- A viewer with an `IN_PROGRESS` result receives `answer: null`.
- A viewer with a `COMPLETED` result receives the answer.
- A viewer with a `FAILED` result receives the answer.
- The shared author's terminal result still produces the answer for the author.
- The legacy `getFeed` path and the visual `getFeedView` path apply the same rule.
- The feed does not issue one viewer-result query per daily-game item.

## Documentation impact

Update the feed contract and business rules to state that the daily-game answer is viewer-dependent: a published terminal result is visible to accepted followers, but its answer is redacted until the viewer's own result for that challenge is terminal. Add the shipped behavior to `docs/context/progress.md` and keep the database schema unchanged.

## Non-goals

- Hiding the entire daily-game feed item.
- Changing who can see a shared result.
- Changing `sharedAt` retention or adding feed expiration.
- Retaining or exposing historical attempt details.
