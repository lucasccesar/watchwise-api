# Content Details External IDs

## Goal

Expose TMDB external identifiers in the existing content details response without adding separate HTTP calls. Movie, series, and episode detail requests must include IMDb identifiers. Movie and series responses must also include Facebook, Instagram, and Twitter identifiers.

## Design

Extend the existing TMDB detail requests with `external_ids` in `append_to_response`:

- Movie details: append `external_ids` to the existing movie append list.
- TV series details: append `external_ids` to the existing TV append list.
- TV episode details: append `external_ids` to the episode detail request.

Add a shared `TmdbExternalIds` response model mapped from TMDB snake_case fields. Add this model to movie, TV, and episode full-detail models through the `external_ids` JSON property. No new client method, cache, or HTTP request is needed.

Extend `ContentDetailsDTO` with `imdbId`, `facebookId`, `instagramId`, and `twitterId`. The details service maps IMDb for movies, series, and episodes. It maps the social identifiers for movies and series and leaves all four fields `null` for seasons. Missing values from TMDB remain `null`.

## Error handling

The existing detail lookup remains responsible for determining whether the content detail is available. Since `external_ids` is part of that response, a missing identifier does not fail the request and is serialized as `null`. Existing TMDB retry and not-found behavior remains unchanged.

## Testing

- Verify movie, TV series, and TV episode detail requests include `external_ids` in `append_to_response`.
- Verify external ID JSON is deserialized, including snake_case fields.
- Verify content details map IMDb and social IDs for movies and series, IMDb only for episodes, and nulls when identifiers are absent.
- Run the focused TMDB and content detail tests, followed by the full Maven test suite.
