# Community and TMDB ratings

## Goal

For series, return four recent episodes from the content page instead of three. Whenever an API response exposes a public community average for a content item, expose the matching TMDB vote average as `tmdbVoteAverage` as well.

## Scope

- Raise the recent episode limit from three to four in the TMDB detail result and in the page's child-card section. Preserve the existing released-date filtering and ordering.
- Pair `tmdbVoteAverage` with public averages in content stats, page stats, content cards, person-credit cards, user-list item rows, and the episode-rating grid. The grid carries the series TMDB average beside its overall community average and an episode TMDB average beside each episode's community average.
- Preserve existing paired values in Trending, Watchlist, Home next episodes, and series-in-progress responses.
- For user-list rows, pair TMDB's rating for the listed content with `globalEpisodeAverageRating` and `contentAverageRating`. `episodeAverageRating` remains an owner-specific average and is outside the community-average rule.
- Leave individual user ratings, personal rating summaries, and review publication scores unchanged.

## Data and failure behavior

Read ratings from existing TMDB detail/card metadata. Reuse responses and caches already used by these flows, with bounded batch lookups for list results. Do not store TMDB values on `Content` or add a migration.

An absent TMDB vote average is returned as `null`. If an optional TMDB rating lookup fails on a stats or list response, keep the community stats and return a null TMDB rating. Existing endpoints that already require TMDB details keep their current error behavior.

## API and documentation

Additive response fields use the existing `tmdbVoteAverage` name and remain nullable. Update `docs/context/openapi.yaml` and append the shipped behavior to the current-day section of `docs/context/progress.md`.

## Verification

Review every DTO that exposes a public average, ensure paged list lookups only resolve the returned page, check that both series recent-episode arrays contain four entries when four eligible episodes exist, compile with the Maven wrapper while skipping tests, and refresh the local graphify graph. Tests will not be added or run unless requested.
