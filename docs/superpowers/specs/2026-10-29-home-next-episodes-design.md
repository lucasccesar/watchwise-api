# Home `nextEpisodes` design

## Problem

`SummaryServiceImpl.getHomeSummary` requests only the first four in-progress series. `HomeNextEpisodeAssembler` omits any series whose next episode could not be resolved, so the response can contain fewer than four cards even when later candidates have a usable next episode. The progress query already ranks by the latest effective watch date, but ties use the series TMDB ID, so two episodes recorded on the same date can appear in an order unrelated to when their diary entries were added.

## Design

Keep the response capped at four cards. Read the existing paginated series-progress results in their current order and continue to later pages only while fewer than four cards have been assembled and another page exists. Ignore duplicate series IDs if pagination shifts during concurrent diary updates. If TMDB becomes unavailable on a later page, retain cards already assembled; if the first page cannot be read, preserve the current empty-list behavior and return the Home summary normally.

For `LAST_WATCHED`, rank each series by the most recent diary event's effective watched date descending, then that event's `created_at` descending, then series TMDB ID ascending for deterministic ties. Within a series, keep selecting its most recent event by the same date, creation-time, and diary-entry-ID ordering. The response shape and the four-card maximum remain unchanged.

## Trade-offs and scope

The Home may read additional progress pages and resolve extra episode metadata when a candidate has no usable next episode. It stops once four cards are assembled or candidates are exhausted. No schema, route, or response-field changes are needed. Update the implemented business-rule documentation and the chronological progress log with the behavior change.
