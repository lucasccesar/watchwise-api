# Watchlist episode count design

## Goal

Return episode counts and both external and community ratings on each item in `GET /users/{userId}/watchlist`.

## API behavior

- Add nullable `numberOfEpisodes`, `tmdbVoteAverage`, and `communityAverageScore` to each `WatchlistEntry` response item.
- For a `SERIES`, use the `number_of_episodes` value from TMDB's series details.
- For a `MOVIE`, or when TMDB has no usable episode count, return `null` for `numberOfEpisodes`.
- Use TMDB's `vote_average` for `tmdbVoteAverage`; leave it null when unavailable.
- Use the existing public aggregate for `communityAverageScore`; leave it null when there are no public ratings.
- Keep the count read-only and do not persist TMDB metadata on `Content`.
- Populate the existing `ContentCard.numberOfEpisodes`, `ContentCard.tmdbVoteAverage`, and `ContentCard.stats.averageScore` fields as well. This makes all three values available inside cards returned by `GET /users/{userId}/watchlist/view`.

## Data flow

Extend `TmdbCardMetadata` and `TmdbClient.tvCardMetadata` to carry the series episode count. `ContentCardAssembler` maps it to its existing `ContentCardDTO.numberOfEpisodes` field. Keep TMDB vote average in the same cached metadata value and request `TMDB_VOTE_AVERAGE` for watchlist card assembly. The assembler already supports `STATS` and maps the public community average to `ContentCardDTO.stats.averageScore`.

For `GET /users/{userId}/watchlist`, assemble the requested page's movie and series metadata and stats using the target user's preferred language and existing TMDB cache. Copy the resulting count, TMDB vote average, and community average into top-level `WatchlistEntryResponseDTO` fields. Only series can have an episode count. Movies still resolve ratings. For `GET /users/{userId}/watchlist/view`, request the already-defined TMDB vote-average field alongside the existing stats field; its nested card exposes the same values without adding new view DTO properties.

The existing `ContentCardAssembler` treats unavailable card metadata as partial or unavailable and leaves absent values null. The optional episode count and TMDB vote average follow that behavior and do not add a new failure path to watchlist reads. The community average comes from the same public aggregate as content cards and remains null when no public score exists. Existing release-date resolution and `upcomingCount` behavior remain independent.

## Scope and trade-offs

The shared card and stats paths avoid duplicate TMDB resolution and community aggregation. They fill fields already present in the card DTO and OpenAPI contract. The page response gains three top-level fields while `watchlist/view` uses its existing nested card fields. Metadata and statistics are resolved only for the requested page; a movie-only page does not request episode counts.

No schema migration or content persistence change is needed. Update the OpenAPI contract, business rules if the resolved metadata behavior merits an entry, and the current chronological progress entry when implementation lands.

## Acceptance criteria

- A series with TMDB `number_of_episodes` returns that value as `numberOfEpisodes` in `GET /users/{userId}/watchlist` and in the card model.
- A movie returns a null episode count while still returning available ratings.
- TMDB vote average is returned as `tmdbVoteAverage` in page items and cards when available.
- The public community average is returned as `communityAverageScore` in page items and as `stats.averageScore` in cards; both remain null when there are no public ratings.
- Missing or unavailable TMDB metadata leaves the related fields null without changing the watchlist response's existing error behavior.
- The endpoint contract and progress documentation describe the shipped behavior.
