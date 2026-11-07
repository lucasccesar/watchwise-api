# Daily Game Info and Filmography Play Design

## Goal

Support the Movie by info, Series by info, Actor by movie filmography, and Actor by series filmography screens with one additive gameplay read model that gives the frontend presentation-ready data without changing the existing raw game contracts.

## Context

The four existing image-based play screens use `GET /games/{gameType}/play` and its visual action routes. The information and filmography games currently use raw daily-game state and attempt routes. The raw responses contain the game logic, but the frontend must join attempt data, filmography keys, actor metadata, and screen timing by itself.

## Decision

Extend the existing `DailyGamePlayService` and `/games/{gameType}/play` routes to all eight `DailyGameType` values. Do not create a second family of information or filmography endpoints. Keep `GET /games/{gameType}/today`, raw attempt routes, raw DTOs, and their direct-image behavior compatible.

The visual play DTO remains a common envelope containing the challenge date, next UTC reset, status, attempts, score, potential score, action permissions, sharing state, and terminal answer. It gains one optional typed screen section, `filmography`, for actor games. `DailyGameAttemptViewDTO` gains nullable `infoFeedback` for information games.

The existing `DailyGameInfoFeedbackDTO` remains the one information-comparison model. It gains only nullable `candidateEpisodeCount`, which is display metadata for Series by info and does not affect correctness. The four existing image games keep their current JSON fields; additions are nullable and additive.

## Frozen data and read behavior

Gameplay reads must use the daily challenge snapshot and `attempt_details` only. They must not create `Content`, call `/contents/details`, or call TMDB to rebuild an already-played screen.

At generation time, series-information snapshots must retain both `numberOfSeasons` and `numberOfEpisodes`. The comparison already loads the selected series details while validating an attempt, so it writes the candidate episode total into persisted information feedback. This avoids widening the generic candidate identity merely for one screen field.

Filmography snapshots already retain each work's `period`, role episode count, total episode count, and genres. A visual-only filmography assembler must expose the stored period and role episode count, without changing the raw filmography projection. It derives the actors shared with each work by inverting persisted per-attempt shared-work keys. Actor cards derive avatar and birthday from already-persisted candidate metadata in `attempt_details`; age is calculated against `challengeDate`, not the browser clock.

## API shape

`DailyGamePlayViewDTO` gains nullable `filmography`. `DailyGameAttemptViewDTO` gains nullable typed `infoFeedback`; filmography feedback stays internal to the visual filmography assembler instead of becoming another caller-facing field.

`filmography` contains:

- the resolved `majorRoles` state;
- actor previews with identifier, name, profile URL, birth date, age on challenge date, all-role shared-work count, and major-role shared-work count;
- works with identifier, conditional title, reveal/highlight flags, year, genres, role episode count, period, and the matching actor previews in `sharedWith`.

For the series information screen, `revenueOrSeasons.displayValue` carries the candidate season count and `infoFeedback.candidateEpisodeCount` carries the candidate episode count. The comparison remains seasons-only; episode count is presentation metadata and does not affect correctness.

For information and filmography games, open-state `media` is empty. At terminal state, the answer image is issued through the existing protected media-grant service, never as a direct TMDB URL.

## Error, authorization, and compatibility rules

- `GET /play` remains authenticated and read-only.
- `POST /play/attempt` and `POST /play/give-up` require CSRF and use the existing throttles.
- The visual action path rejects duplicate current-day candidates under the locked mutation path; raw attempts retain their current behavior.
- A malformed or unavailable frozen snapshot returns the existing daily-games-unavailable error rather than calling TMDB as a read fallback.
- Existing raw route JSON remains compatible; new raw snapshot fields are additive only.

## Rejected alternatives

1. Build the screens directly from raw routes in the frontend. This works, but spreads joins and age calculation into each client and leaves timing unavailable on an individual game response.
2. Create four independent endpoint families. This duplicates mutations, authorization, and test coverage without adding a distinct domain boundary.
3. Fetch TMDB details on every play read. This increases latency and makes a frozen daily challenge depend on mutable remote data.

## Verification

Unit tests cover snapshot and attempt-detail round trips, typed feedback projection, actor age calculation, shared-with inversion, period preservation, terminal image redaction, and rejection of invalid game-route input.

Controller and integration tests cover all four new game types through the existing `/play` route, authenticated/CSRF behavior, raw-route compatibility, duplicate action behavior, and zero TMDB calls for a visual read after persisted state exists.
