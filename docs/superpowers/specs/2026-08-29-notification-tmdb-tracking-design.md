# Notification feature — TMDB change tracking design

Date: 2026-08-29
Status: Approved (design phase), not yet implemented — build order places `Notification` at Fase 7, after `Comment`/`Like`.

## Problem

`Notification` needs to alert users about TMDB-side events, none of which the backend currently has any way to detect:

1. Movie/series in the user's watchlist is released.
2. New episode airs for a series the user is watching (in-progress in the diary).
3. Movie/series in the user's watchlist gets a release date announced (previously unknown/TBA).
4. Movie/series is cancelled.
5. Series is renewed.
6. New movie/series credit appears for a person the user follows (`FollowedPerson`).

The backend has **no TMDB client of any kind today** — all TMDB interaction so far is client-side; the backend only stores `tmdbId`/`type` references (`Content`). Detecting these six events requires the backend to start polling TMDB itself. The central constraint driving every decision below: do this without hammering the TMDB API or making the job prohibitively expensive as the user base grows.

## Key insight enabling the design

Tracked TMDB entities are **deduplicated across users**, not per-user. The set of distinct `(tmdbId, type)` pairs across every user's watchlist, in-progress series, and the set of distinct `personTmdbId`s across every `FollowedPerson` row, grows with catalog diversity — not with user count. A popular title watchlisted by thousands of users still costs exactly one TMDB call per check cycle. This is what keeps the whole approach cheap at scale.

Freshness requirement (confirmed with user): **up to 24h delay is acceptable for every notification type.** This rules out needing a real-time/webhook-shaped design and justifies a simple daily batch job.

## Decisions

### 1. Two scheduled jobs, no real-time path

- **`ContentTrackingJob`** — daily (`@Scheduled(cron = ...)`, same pattern as `RefreshTokenCleanupJob`/`RateLimiterCleanupJob`). Covers `RELEASE`, `ANNOUNCED_DATE`, `CANCELLED`, `RENEWED`, `NEW_EPISODE`.
- **`FollowedPersonTrackingJob`** — weekly. Covers `FOLLOWED_PERSON_NEW_CREDIT`. Lower cadence because a followed person's new project announcement isn't time-sensitive the way a release date is, and per-person credit lookups don't share a call the way title lookups do.

Rejected alternatives:
- **Live TMDB check on every relevant user request** (e.g. when viewing a watchlist) — doesn't produce a proactive notification, defeats the purpose.
- **TMDB `/movie/changes` and `/tv/changes` endpoints** (global daily changes, intersected locally with the tracked set) — only pays off once the tracked set is larger than TMDB's daily global change volume. For an early-stage app this is very unlikely to be true, and it adds implementation complexity (pagination through global changes) for no benefit yet. Revisit if/when the tracked set grows large enough to make this comparison favorable.

### 2. One TMDB detail call covers multiple notification types

`/movie/{id}` and `/tv/{id}` already return `release_date`, `status`, and (series) `next_episode_to_air` in a single response. `ContentTrackingJob` therefore makes exactly **one call per distinct tracked `Content`** per run, not one call per notification type.

### 3. New TMDB client, shared/cross-cutting

`common/tmdb/TmdbClient`, built on Spring's `RestClient` (no new dependency — Spring Boot ships it). This is the first backend-side TMDB integration; placing it under `common` (not inside `notification`) is deliberate since other features (`/search`, future content-detail hydration) will need it too.

### 4. New cache tables to detect diffs (divergence from "Content never stores TMDB metadata")

Confirmed with user: acceptable to introduce a small tracking-only cache, kept explicitly separate from `Content` and never exposed via API.

- **`TrackedContentState`** — one row per tracked `Content` (FK unique on `contents.id`, reusing the existing reference instead of duplicating `tmdbId`/`type`). Columns: `lastKnownReleaseDate`, `lastKnownStatus` (raw TMDB status string), `nextEpisodeAirDate`, `nextEpisodeSeasonNumber`, `nextEpisodeNumber`, `lastCheckedAt`.
- **`TrackedPersonState`** — one row per distinct `personTmdbId` followed by anyone. Columns: `personTmdbId` (unique), `lastCheckedAt`.
- **`TrackedPersonCredit`** — child of `TrackedPersonState`. Columns: `creditTmdbId`, `creditType` (MOVIE/SERIES). Unique constraint `(tracked_person_state_id, credit_tmdb_id)`. Records every credit already seen for that person, so a new entry in TMDB's `combined_credits` response is detectable.

This needs a one-line addition to `CLAUDE.md`'s "Avoid" section documenting this as a narrow, explicit exception (tracking-only cache, not user-facing `Content` metadata) alongside the existing six metadata exceptions, at implementation time.

### 5. `Notification` entity — enum expansion

`openapi.yaml`'s current `Notification.type` enum (`RELEASE`, `CANCELLATION`, `DELAY`) predates this design and doesn't cover the real six cases. Replace with: `RELEASE`, `ANNOUNCED_DATE`, `CANCELLED`, `RENEWED`, `NEW_EPISODE`, `FOLLOWED_PERSON_NEW_CREDIT`.

Fields: `id`, `user` (recipient FK), `type`, `message`, `content` (FK, always present — for `FOLLOWED_PERSON_NEW_CREDIT` this is the new credit's `Content` reference, obtained via the existing idempotent `ContentService.getOrCreateReference`), `personTmdbId` (nullable, only set for `FOLLOWED_PERSON_NEW_CREDIT`), `isRead`, `createdAt`/`updatedAt`. `personTmdbId` is a new field not in the current `openapi.yaml` schema — needs adding.

`ON DELETE CASCADE` from `User` and `Content`, consistent with every other user-owned table.

### 6. Diff logic per notification type

| Type | Condition (comparing fresh TMDB response to `TrackedContentState`) |
|---|---|
| `ANNOUNCED_DATE` | `lastKnownReleaseDate` was null/different, new response has a release date, and that date is still in the future |
| `RELEASE` | `lastKnownReleaseDate` existed and was in the future; today is now `>= release_date` |
| `CANCELLED` | `lastKnownStatus != "Canceled"` and new `status == "Canceled"` |
| `RENEWED` | Status moves from `Ended`/`Canceled` to `Returning Series`, or a new season number appears beyond the last known one. Heuristic — TMDB has no literal "Renewed" status. |
| `NEW_EPISODE` | Previously known `nextEpisodeAirDate` is now `<= today` (episode has aired); cache is updated with the fresh `next_episode_to_air` from the same response |

Diff detection lives in a pure, independently-testable component (`ContentChangeDetector` or similar) — no `@Scheduled`/HTTP concerns mixed in.

### 7. Fan-out

Each positive diff triggers a query for every affected `userId` (via `WatchlistEntry` for `RELEASE`/`ANNOUNCED_DATE`/`CANCELLED`/`RENEWED`, via in-progress `DiaryEntry` for `NEW_EPISODE`, via `FollowedPerson` for `FOLLOWED_PERSON_NEW_CREDIT`) and a batch insert of one `Notification` per user. This is a DB-only cost, not a TMDB call — it scales with user count safely because it never touches the external API.

### 8. Resilience

- Each tracked item (one `Content` in `ContentTrackingJob`, one person in `FollowedPersonTrackingJob`) is processed in its **own transaction** — one failing item is logged and skipped, never aborts the whole job run.
- Diff detection, `Notification` creation, and `TrackedContentState`/`TrackedPersonCredit` update happen in the **same transaction** per item — a mid-commit failure leaves nothing persisted, so the next run re-evaluates that item cleanly (no duplicate or lost notifications).
- `TmdbClient` uses a short timeout and a single retry per call; a call that still fails just skips that item for the current run.
- Bounded concurrency (e.g. a small fixed pool) across TMDB calls within a run — not because TMDB enforces a documented rate limit (the old 40 req/10s cap was removed in 2020), but as a courtesy/safety margin.
- Running either job twice in the same day is safe by construction: the diff is against already-persisted state, so a second run within the same window finds no change and creates nothing extra.

### 9. Testing

- Unit tests for `ContentChangeDetector`, one per row of the diff table in §6, against a fake old state + fake fresh TMDB response.
- Unit tests for the jobs' orchestration/fan-out, mocking `TmdbClient` and repositories, asserting exactly which users get notified via `ArgumentCaptor` (not just "was called").
- `TmdbClient` tested with `MockRestServiceServer` (already available via `spring-boot-starter-test`, no new dependency) against sample TMDB JSON payloads and error cases (timeout, 404, 500).
- No integration test hits the real TMDB API, in CI or locally.

## Explicitly out of scope for this design

- **"Can't log a `DiaryEntry` for unreleased content."** The user decided this validation lives entirely in the frontend, not the API. Noted trade-off: a client bypassing the official frontend could still create a `DiaryEntry` for unreleased content — a data-quality gap, not a security issue, and the user chose to accept it rather than add backend validation.
- Real-time/near-real-time delivery — 24h batch latency was confirmed acceptable for all six notification types.
- TMDB `/changes` endpoint-based tracking — deferred until the tracked-set-vs-global-changes-volume trade-off actually favors it.

## Follow-up doc updates required at implementation time

- `openapi.yaml`: expand `Notification.type` enum, add `personTmdbId` field.
- `database-schema.html`: add `TrackedContentState`, `TrackedPersonState`, `TrackedPersonCredit` (or an equivalent English note that these are internal, non-domain tables — they're not part of the PT logical model since they didn't exist in the original spec).
- `CLAUDE.md`: document the `TrackedContentState`/`TrackedPersonState`/`TrackedPersonCredit` cache as a narrow tracking-only exception to "`Content` never stores TMDB metadata", alongside the existing six metadata exceptions.
- `business-rules.md`: the `RENEWED` heuristic and the diff table in §6, since these are non-obvious domain decisions.
- `progress.md`: once actually implemented (this design session produced no shipped code, so no entry yet per the project's "only log shipped work" rule).
