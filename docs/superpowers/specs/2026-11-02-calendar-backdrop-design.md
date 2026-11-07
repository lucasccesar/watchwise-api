# Calendar response backdrop design

## Goal

Add `backdropPath` to the top-level response from `GET /users/me/calendar?month=YYYY-MM`, using the backdrop for the first event returned for that month.

## Behavior

- The first event is the first item in the existing calendar event order.
- For a movie event, resolve metadata for the movie TMDB ID.
- For an episode, season, or series event, resolve metadata for its parent series TMDB ID.
- Return the backdrop as a TMDB `w1280` image URL, matching other `backdropPath` fields.
- Return `null` when there are no events, the first event has no backdrop, or optional backdrop metadata cannot be resolved. The existing calendar schedule error behavior remains unchanged.

## Implementation boundary

Reuse `TmdbCardMetadataResolver` for one metadata lookup, which uses the existing TMDB metadata cache. Keep backdrop resolution out of calendar schedule snapshots and do not add a database migration. Add the nullable field to `CalendarResponseDTO` and document it in `docs/context/openapi.yaml`; record the implemented calendar rule in `docs/context/business-rules.md` and the shipped change in `docs/context/progress.md`.

Individual event content DTOs remain unchanged. The response envelope alone carries the backdrop selected from the first event.
