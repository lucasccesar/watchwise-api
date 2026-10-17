# Search Screen Read Model Design

## Objective

Provide one paginated visual read model for the Search screen's Content, Movies, Series, and People tabs. The response must render the supplied designs in one request, without clients combining raw search results with content-details calls.

## Constraints and non-goals

- Preserve `GET /search` and its current `SearchResultDTO` exactly. It remains the compact, multi-purpose search contract used by existing flows.
- Add, rather than replace, the visual contract at `GET /search/view`.
- Limit this work to `CONTENT`, `MOVIE`, `SERIES`, and `PERSON`. Lists, Picks templates, and users retain their existing raw-search path.
- Do not create `Content` rows solely because an external result appeared in a search result.
- Reuse `ContentCardAssembler` for local viewer state and cached TMDB card metadata. The frontend must not issue one details request per card.
- Treat TMDB score and Watchwise score as separate values. The search card exposes TMDB's `vote_average`; it does not relabel a Watchwise aggregate as a TMDB score.

## Public API

`GET /search/view?q={query}&scope={CONTENT|MOVIE|SERIES|PERSON}&page={page}&size={size}`

- `q` is trimmed and must contain 3 through 100 characters.
- `scope` is required.
- `page` defaults to `1`, is at least `1`, and cannot exceed `500`. `totalResults` remains the exact
  external total, while `totalPages` and `hasNext` describe only the accessible visual pages and are
  capped at `500`; this prevents the response from advertising a next page the route rejects.
- `size` defaults to `18`, is at least `1`, and cannot exceed `20`.
- The route requires the authenticated user and uses the existing search rate-limit bucket. Movie search
  passes the viewer's preferred TMDB language and region; TMDB's TV and person search endpoints accept
  language but do not expose a region parameter. Card enrichment uses both viewer preferences.

The response is a `SearchViewDTO`:

~~~java
public record SearchViewDTO(
        SearchViewScope scope,
        String query,
        int page,
        int size,
        int totalPages,
        long totalResults,
        boolean hasNext,
        List<SearchContentViewDTO> contents,
        List<SearchPersonViewDTO> people) { }
~~~

Exactly one of `contents` and `people` is populated. Empty result sets have `totalResults = 0`, `totalPages = 0`, and `hasNext = false`.

`SearchContentViewDTO` is a purpose-built response with TMDB id, movie-or-series type, title, fully resolved `posterUrl`, release year, genres, runtime, season count, TMDB `voteAverage`, viewer state, and preview status. It is assembled from `ContentCardDTO` but never exposes the card object itself. This keeps the client contract stable when optional enrichment is unavailable: raw TMDB title, type, year, and poster remain renderable. `SearchPersonViewDTO` contains the TMDB id, name, fully resolved profile URL, and `knownForDepartment`.

## Content paging semantics

TMDB's multi-search total cannot be used for the Content tab because it includes people. Filtering its items would make the total and numeric pages incorrect. The Content tab therefore uses the movie and TV searches as its sources.

The canonical combined order is deterministic:

1. Interleave the two TMDB relevance-ordered streams beginning with a movie: movie 1, series 1, movie 2, series 2, and so on.
2. Once one stream is exhausted, append the remainder of the other stream in its TMDB order.
3. `totalResults` is `movieTotalResults + seriesTotalResults`; pagination slices this combined sequence.

This deliberately favors correct totals, stable numeric pagination, and a balanced mixed grid over the opaque mixed relevance order of TMDB's `/search/multi`. It is the only source contract available that can state an exact content-only total without storing a separate external search index.

`TmdbSearchWindowReader` is the shared deep module for one TMDB source. TMDB pages are always 20 items, while the visual endpoint accepts a size from 1 through 20. The reader first loads source page 1 to learn the authoritative total, then converts the requested visual offset into the one or two valid overlapping TMDB pages and slices exact logical items from them. This prevents the page-two gap that would occur if visual `size = 18` were passed directly as TMDB's `page = 2`, and lets an out-of-range visual page return an empty page with the true result total rather than becoming an external error. Reported accessible page metadata remains capped at 500.

`ContentSearchPagePlanner` is a pure component that maps a requested combined page to typed source positions. It uses the movie and TV totals returned by `TmdbSearchWindowReader`, then requests only source windows that contain those positions. After the cached total page, a source contributes at most two fixed-size TMDB pages for the requested visual window. It never walks every preceding result or makes a per-card search call.

## Enrichment and performance

Movie and TV search DTOs gain only `vote_average`; person search DTOs gain `known_for_department`. Search candidates are converted to `ContentCardSpec` values and assembled once per response with `BASIC_METADATA`, `VIEWER_STATE`, and `WATCHLIST_PROGRESS`. No unused TMDB fields are exposed merely because TMDB returns them.

`ContentCardAssembler` uses its bounded eight-worker metadata executor, cache, and single-flight lookup behavior. A cold card may require a TMDB metadata lookup to obtain runtime, genre names, and number of seasons; concurrent or repeated requests share that work. This is a bounded server-side enrichment cost for a maximum of 20 cards, not browser-side N+1 traffic. If TMDB cannot provide optional metadata, the card remains usable with `previewStatus = PARTIAL` and nullable optional fields.

## Error handling and safety

- A TMDB unavailable result maps to the existing `TmdbUnavailableException`; the global exception handler returns the project-standard `ApiError`.
- The response exposes only public TMDB metadata plus the authenticated viewer's own state. It never leaks another user's private activity.
- Page, size, scope, and query binding failures continue through the project-wide validation/error handler.
- The visual query does not write to the database and does not bypass the existing `ContentViewerStateService` visibility rules.

## Tests and documentation

Tests cover request validation, raw-contract preservation, source-page planning, correct mixed totals, source exhaustion, unavailable TMDB behavior, visual DTO mapping, and the viewer state fields requested from `ContentCardAssembler`.

The implementation updates `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/progress.md`, and `docs/telas-reqs-definitivo.md`. Those documentation files remain local worktree documentation under the repository convention and are not included in code commits unless the user explicitly asks for a documentation commit.

