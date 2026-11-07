# Watchlist rating sort design

## Goal

Change `sort=RATING` in `GET /users/{userId}/watchlist/view` to sort by the public Watchwise community average first and TMDB vote average second.

## Ordering behavior

Sort candidates lexicographically:

1. `ContentCard.stats.averageScore` (the public Watchwise community average), using the requested direction.
2. `ContentCard.tmdbVoteAverage`, using the same direction, only when the community averages compare equal.
3. Watchlist entry UUID ascending, preserving the existing stable final tie-breaker.

Nulls stay last for each score in both directions. A missing community score does not fall back to TMDB as the primary value; TMDB can order items within the group whose community score is null. A null TMDB score falls through to the UUID tie-breaker.

## Data flow and limits

The `RATING` sort already materializes public stats for all candidates after type, genre, and status filters. Extend that materialization to request `BASIC_METADATA` and `TMDB_VOTE_AVERAGE` as well, then compare the card's public average and TMDB average in the comparator. The existing metadata resolver and cache provide the TMDB value. Keep the existing materialization limit and null handling.

This change applies only to the visual watchlist endpoint, which exposes the `RATING` sort. The paged `GET /users/{userId}/watchlist` keeps its global position order because it has no sort parameter. No database or persistence changes are needed.

## Documentation

Update the visual endpoint's OpenAPI description, the WatchlistEntry business rule, and the current chronological progress entry to state the primary and secondary rating keys.

## Acceptance criteria

- With different public community averages, the community average determines order.
- With equal community averages, TMDB vote average determines order using the selected direction.
- Null community averages remain after scored entries, but TMDB may order entries within the null group; missing TMDB averages remain last within a tie.
- The UUID ascending tie-breaker is unchanged.
- `DATE_ADDED`, `RELEASE_DATE`, `RUNTIME`, `TITLE`, the simple paged watchlist order, and the materialization limit remain unchanged.
