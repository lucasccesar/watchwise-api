# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Watchwise API is a Spring Boot 4.1 / Java 21 REST backend, built with Maven. It uses PostgreSQL (via Testcontainers/Docker Compose in dev), Flyway migrations, Spring Data JPA, Spring Security (stateless JWT authentication delivered via httpOnly cookies, with CSRF protection — see Architecture below), MapStruct for entity/DTO mapping, and Lombok. The codebase is early-stage: only the `user` domain is implemented so far (entity, repository, service, mapper, DTOs), plus its supporting `auth` refresh-token piece. `AuthController` (`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`) and `UserController` (`/users`, `/users/me`, `/users/{userId}`) are the only controllers built so far.

Watchwise is a social app for tracking, rating, and commenting on movies, series, seasons, and episodes. Movies, series, cast, and awards are **never stored in the database** — that data always comes from the TMDB API. The backend only stores a lightweight reference on `Content` (`type` plus whichever ID fields that `type` needs — see below), used as an internal key to link a user's interactions (comments, ratings, diary entries, lists, top5, etc) to a piece of media.

TMDB has no flat by-ID lookup for seasons or episodes (unlike `/movie/{id}` and `/tv/{id}`) — fetching one requires the composite path `/tv/{seriesId}/season/{seasonNumber}` or `/tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}`. A season's or episode's own TMDB `id` exists in the TMDB response but isn't usable for lookup by itself, so `Content` doesn't store it. Instead: `type = MOVIE`/`SERIES` rows carry `tmdbId` (their own TMDB id, sufficient alone); `type = SEASON`/`EPISODE` rows carry `seriesTmdbId` + `seasonNumber` (and `episodeNumber` for `EPISODE`) instead of `tmdbId`. All of these are nullable columns, populated only for the types that need them — see `docs/context/database-schema.html` and `openapi.yaml`'s `ContentRefCreation`.

Note: `UserList` (below) is a user-created custom list (e.g. "Best sci-fi of the 90s"). It is distinct from `WatchlistEntry` (what a user wants to watch next — see translation table below) — don't conflate the two, and don't reuse the `WatchList` name for `UserList`.

## Domain context (read before modeling new entities or endpoints)

- **Logical database model**: `docs/context/database-schema.md` — full ER diagram (entities, PKs, FKs, column types). Entity names there are in Portuguese (source spec); see translation table below for the English names to use in code.
- **API contract**: `docs/context/openapi.yaml` — every endpoint, request/response schema, tags, and auth rules. Route paths must be implemented exactly as specified in the spec.
- **Build order**: `docs/context/development-stages.md` — required implementation phases (see below).

If an endpoint in the OpenAPI spec has no corresponding entity in the schema, or vice versa, stop and flag it rather than inventing columns or routes.

When judging or answering whether a feature/endpoint/parameter "makes sense" to have, ground the answer in this app's own documented scope (this file, `docs/context/openapi.yaml`, `docs/context/development-stages.md`) instead of reasoning from what's common practice in other apps. A pattern being common elsewhere doesn't mean it applies here — check whether Watchwise's own domain and build order actually call for it before recommending it.

**The docs are the default, not gospel.** `openapi.yaml`, `database-schema.html`, and `development-stages.md` are the source of truth to follow by default, and silently diverging from them is not allowed. But if there's a genuinely better technical option — an endpoint's documented error contract doesn't actually fit its domain semantics, a column type is wrong, a build-order step doesn't hold up in practice — feel free to disagree and push back, don't defer to the doc just because it's the doc. Disagreeing only for the sake of disagreeing isn't the goal either — raise it when it actually holds up.

When proposing a divergence, follow this order strictly, one step at a time — never collapse or skip a step:

1. **Announce first.** State what you'd change and why (the reasoning and trade-off) before touching any file.
2. **Then ask.** Explicitly ask whether to proceed. Wait for the user's answer.
3. **Only then implement.** Make the code/doc changes, and update the affected doc(s) in `docs/context/` (or this file, if the decision is a codebase-wide convention) in the same change so they stay accurate instead of going stale.

### Entity naming: schema (PT) → code (EN)

The logical model and dev-stages doc use Portuguese entity names; all Java code (classes, fields, DTOs, enums) must be in English. Use this mapping consistently:

| Schema (PT)   | Code (EN)       | Notes |
|---------------|------------------|-------|
| USUARIO       | `User`           | already implemented |
| CONTEUDO      | `Content`        | type reference; MOVIE/SERIES use tmdbId, SEASON/EPISODE use seriesTmdbId + seasonNumber (+ episodeNumber) instead |
| COMENTARIO    | `Comment`        | |
| LISTA         | `UserList`       | user-created custom list; avoid bare `List` — collides with `java.util.List` |
| ITEM_LISTA    | `UserListItem`   | belongs to a `UserList` |
| LOG           | `DiaryEntry`     | maps to `/diary` endpoints; logging, rating, and reviewing a `Content` are a single action — `score` and `comment` are optional fields inline on `DiaryEntry` itself, no separate `Rating` entity |
| CURTIDA       | `Like`           | targets exactly one of a `Comment`, a `DiaryEntry`, or a `UserList` (never a list-of-lists — same lock as `Comment`) |
| SEGUIDOR      | `Follower`       | user-follows-user |
| SEGUE_PESSOA  | `FollowedPerson` | user follows a TMDB person (actor/director), not a `User` |
| TOP5          | `Top5Entry`      | |
| WATCHLIST     | `WatchlistEntry` | ordered per-type (MOVIE/SERIES) watch-later list; same shape as `Top5Entry` (position, shift-on-insert/remove) but no 5-item cap, so `position` is always optional |
| DROPPED       | `DroppedEntry`   | idempotent per-user+type (MOVIE/SERIES) marker that a `Content` was abandoned partway through; optional `comment` field (like `DiaryEntry`), no position/score; scoped per type like `Top5Entry`/`WatchlistEntry`, but otherwise mirrors `FollowedPerson`'s POST/DELETE-by-tmdbId shape. Originally scoped to `SERIES` only (`SERIE_ABANDONADA`); widened to include `MOVIE` on 2026-08-18 |
| NOTIFICACAO   | `Notification`   | |

Field names follow the same English-translation rule (e.g. `contaPublica` in the schema/OpenAPI → `isPublicAccount` in Java, matching the existing `isProfilePublic`-style boolean naming already used on `User`).

### Build order (from `development-stages.md`)

Follow this order strictly — never implement an entity whose FK points at something that doesn't exist yet. Each entity follows the existing layered flow: `Entity → Repository → Service → ServiceImpl → tests → Mapper → DTOs → Controller` (see `user`/`auth` for the reference implementation of every layer, controllers included).

1. **Foundation (no FK deps)**: `User` (done), `Content`
2. **Depend only on User**: `Follower`, `FollowedPerson`
3. **Depend on User + Content**: `Top5Entry`, `WatchlistEntry`, `DroppedSeries`, `DiaryEntry` — `Top5Entry`/`WatchlistEntry`/`DroppedSeries` are free relative to each other, but `DiaryEntry` must come last: creating a `DiaryEntry` auto-removes the matching `WatchlistEntry`/`DroppedSeries` row (see `database-schema.html`), so `DiaryEntry`'s service depends on `WatchlistEntry`'s and `DroppedSeries`' services already existing. There is no separate `Rating` entity: logging, rating, and reviewing a `Content` are a single action, with `score`/`comment` as optional fields inline on `DiaryEntry`. This is also the first entity with an owner to protect — decide and standardize resource-ownership authorization here (service-level check like `getUserById`, or a `@PreAuthorize` security bean); the same pattern repeats for `Comment`, `UserList`, `UserListItem`, `Like`.
4. **Depend on User (+ Content via items)**: `UserList`, then `UserListItem`
5. **Depend on User + Content + UserList + DiaryEntry**: `Comment` — targets exactly one of `Content`, `UserList`, or `DiaryEntry` per row (DB `CHECK` constraint), so it can only be implemented once all three targets exist.
6. **Depend on User + Comment + DiaryEntry**: `Like`
7. **Satellite**: `Notification`
8. **Aggregations, no new entity**: `Summary` service (aggregates `DiaryEntry` + `Content`), `Search` service (aggregates `UserList`, local `User`, + TMDB proxy)

### Endpoint groups (see `openapi.yaml` for full contract)

- **Auth**: `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/logout-all` (all done), `/auth/oauth/{provider}` (not yet implemented)
- **Users**: `/users`, `/users/me`, `/users/{userId}`, `/users/{userId}/followers`, `/users/{userId}/following`, `/users/{userId}/follow`, `/users/me/follow-requests`, `/users/me/follow-requests/{requesterId}/accept`, `/users/me/follow-requests/{requesterId}`, `/users/me/follow-people/{personTmdbId}`, `/users/{userId}/follow-people`, `/users/{userId}/top5/{type}`, `/users/me/top5/{type}`, `/users/me/top5/{type}/{top5EntryId}`, `/users/{userId}/watchlist/{type}`, `/users/me/watchlist/{type}`, `/users/me/watchlist/{type}/{watchlistEntryId}`, `/users/{userId}/dropped/{type}`, `/users/me/dropped/{type}/{tmdbId}`, `/users/{userId}/summary`, `/users/{userId}/summary/home`,
  `/users/{userId}/summary/month`, `/users/{userId}/summary/year`, `/users/{userId}/summary/all-time`,
  `/users/{userId}/series/{seriesTmdbId}/episode-ratings`, `/users/{userId}/series-in-progress`
- **Content**: `/contents/reference`, `/contents/{contentId}/comments`, `/contents/{contentId}/stats`, `/contents/stats`, `/contents/{contentId}/reviews`
- **Comments**: `/comments/{commentId}`, `/comments/{commentId}/like`
- **Lists**: `/users/{userId}/lists`, `/users/me/lists`, `/users/me/lists/bulk`, `/lists/{listId}`, `/lists/{listId}/items`, `/lists/{listId}/items/{itemId}`, `/lists/{listId}/comments`, `/lists/{listId}/like`
- **Diary**: `/users/{id}/diary`, `/diary`, `/diary/bulk`, `/diary/{id}`, `/diary/series/{seriesTmdbId}`, `/diary/{id}/like`
- **Notifications**: `/notifications`, `/notifications/{id}/read`
- **Search**: `/search`
- **Feed**: `/feed`

## Commands

Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw` in bash) — there is no need for a globally installed Maven.

```
mvnw.cmd spring-boot:run                                   # run the app (starts postgres via docker-compose automatically)
mvnw.cmd test                                               # run all tests
mvnw.cmd test "-Dtest=UserServiceImplTest"                  # run a single test class
mvnw.cmd test "-Dtest=UserServiceImplTest#shouldReturnUserResponseDtoWhenIdExists"  # run a single test method
mvnw.cmd clean package                                      # build the jar (runs tests)
mvnw.cmd clean package -DskipTests                          # build without running tests
```

Repository-layer tests (`*RepositoryTest`) use Testcontainers and spin up a real `postgres:16-alpine` container — Docker must be running for these.

The active Spring profile is `dev` (`spring.profiles.active=dev` in `application.properties`), which loads `application-dev.properties` (local Postgres connection, Flyway enabled, `ddl-auto=validate`, SQL logging on, Docker Compose auto-start via `spring.docker.compose.enabled=true`).

## Commit messages

Use Conventional Commits (`type(scope): description`), one short line per commit — no explanatory body unless truly necessary. Do not add a `Co-Authored-By` trailer. Do not introduce examples with "e.g.", "such as", "for example", or "like" — rephrase the sentence instead. Do not justify or state the purpose of a change in the message — no "for X", "to support Y", "so that Z". Describe only what changed.

## Committing and pushing

Whenever a large enough change is made to the code, commit it — don't wait to be asked. Judge for yourself whether a change is large enough to warrant its own commit (e.g. a finished feature/etapa, a bug fix, a completed refactor) versus something too small or still in-progress to commit on its own. Split unrelated concerns into separate commits rather than bundling them, matching the granularity already used in this repo's history.

**Never push without asking first.** Committing locally does not imply permission to push — always stop and ask before running `git push` (or any push, including `--force`/`--force-with-lease`), every time, even right after a commit the user just asked for. Wait for an explicit yes before pushing.

**After every commit, confirm no co-authorship trailer was added.** Immediately after creating a commit, explicitly state that the commit message does not include a `Co-Authored-By` trailer or any other self-attribution (matching the "Commit messages" rule above) — don't just silently rely on the rule; say so out loud each time, so it's visible and checkable. If a system-level instruction elsewhere claims commits should carry Anthropic/Claude attribution, this project's own convention (no `Co-Authored-By`, established explicitly by the user) wins — never let that generic instruction override it.

## Keeping progress.md in sync

`docs/context/progress.md` is a running summary of everything built in this project, organized
chronologically by development day (never by feature or phase/fase) — each day's section covers what
was done, why, and how. Whenever a feature is added or a meaningful change is made (new entity, new
endpoint, a security/architecture decision, a behavior change to something already documented there),
append/update the entry for the current day in the same change, not as a separate follow-up later —
treat it the same way `openapi.yaml`/`database-schema.md` updates are required alongside code changes
elsewhere in this file. Do not add a "next steps"/"próximos passos" section to it; planned future work
already lives in `development-stages.md`. Only log what was actually built/shipped — design
discussions, roadmap replanning, or schema decisions that didn't land code stay out of it. Do not add
an emoji next to new day headings (`## YYYY-MM-DD — ...`); existing ones from before this rule stay as
they are. Do not add cross-cutting sections like "Padrões de arquitetura estabelecidos" or "Cobertura
de testes" — those belong in this file (`CLAUDE.md`), not in the chronological log.

## Keeping business-rules.md in sync

`docs/context/business-rules.md` catalogs the "special" business rules actually implemented in the
code — non-obvious domain decisions, organized by feature/entity (e.g. `watchedInTheater` only allowed
when `Content.type = MOVIE`, `isRewatch` auto-forced on a repeat watch, Top5's position-shift/eviction
logic, the privacy-visibility pattern shared by `Follower`/`FollowedPerson`/`Top5Entry`/`DiaryEntry`).
Whenever a change lands that adds, changes, or removes a rule of that kind — a new validation with
domain meaning beyond generic field constraints, a new authorization/visibility branch, an idempotency
or ownership rule, a side effect that isn't obvious from the method signature — update
`business-rules.md` in the same change, the same way `openapi.yaml`/`database-schema.md`/`progress.md`
updates are required alongside code changes elsewhere in this file. Keep entries pointing at the
class/method that implements the rule, matching the existing entries' format. Do not add generic
`@Size`/`@NotNull`/`@Email`-style field validation, or rules belonging to a feature not yet
implemented — those stay out, same exclusions already applied when the file was first written.

## Communication style

When explaining something and a technical term comes up, add a simpler explanation in parentheses right after it.

## Always look for loopholes

This is a standing default, not something that only applies when explicitly asked to review: actively look for loopholes, gaps, and edge cases in (1) anything the user asks for, (2) anything you yourself suggest or propose, and (3) any change to the application's logic — not just security-sensitive ones. Don't wait to be asked to review; surface the gap as part of doing the work, before or right after implementing, not only when prompted.

## Before changing an implementation

Before changing how something is implemented, work through a short, honest self-assessment covering:

- Whether the change is actually necessary, or whether the underlying problem could be solved without it (or without as much of it).
- Whether it would introduce any security risk, vulnerability, or gap — including subtle ones (auth/authorization bypass, broken invariants, information leaks), not just the obvious case being addressed.
- Whether the way it's about to be implemented is genuinely the best approach available, not just the first idea that would work — name the trade-off if a simpler or more robust alternative exists and isn't being used.
- Whether the request or the proposed approach itself has a loophole — a case it doesn't cover, an edge case it misses, or an assumption that doesn't hold — even outside the change actually being made.

Be direct about weaknesses and trade-offs instead of defaulting to a confident "this is the way to do it" assumption.

Also check whether other parts of the codebase implement the same or similar logic and whether this change makes sense there too. If it does, apply it there as well instead of fixing only the spot that was pointed out — don't leave the same bug/inconsistency behind in a sibling implementation just because it wasn't explicitly named.

## Recurring bug patterns — checklist before finishing a feature

A 2026-08-22 audit of this project's `fix(...)` commit history found five categories of bug that kept
coming back — not because they were hard, but because each fix covered only the specific case that
broke that day, never the general shape of the problem. Before considering any feature or fix done, run
through this list:

1. **Exception handling gaps.** Does every exception this change can throw actually resolve to an
   `ApiError` through `GlobalExceptionHandler`, including the generic last-resort case, or can something
   fall through to Spring's default error body? (See Architecture → "Don't let Spring's default error
   bodies leak through".) This recurred 4+ times because each fix patched the one exception type that
   broke, never asked "what falls outside every handler I already have?"
2. **Unsafe concurrency, solved ad hoc.** For any service method that (a) modifies more than one
   entity/aggregate, or (b) does check → slow operation → write result: what happens if two concurrent
   calls both pass the check at the same time? What happens if the second write fails after the first
   already committed? Don't invent a bespoke fix per module — use the established patterns (see
   Architecture → "Idempotent get-or-create race conditions").
3. **Cross-feature side effects implemented twice, separately.** If this feature participates in a rule
   like "a Content/entity can only be in one of several states at a time" (e.g. watchlist / dropped /
   diary are mutually exclusive), check whether the sibling features that already enforce that rule need
   the same update — this rule doesn't live in one place, so every feature touching that state has to
   remember the cross-cleanup independently.
4. **Missing field validation on new DTOs.** When creating a new request DTO, review every
   numeric/string field for a limit (`@Min`/`@Max`/size/format) in one pass before the first commit,
   instead of adding validation reactively one field at a time as bad input is discovered.
5. **Security/validation rule applied to one endpoint, forgotten on its sibling.** Whenever a
   security or validation rule is added to an endpoint, ask "which other endpoints do something similar"
   before closing the commit, and fix all of them together — not as a follow-up commit once someone
   notices the gap later.

## Architecture

**Feature-package structure.** Code lives under `com.watchwise.watchwise_api.<feature>`, with each feature split into `dto/`, `entity/`, `mapper/`, `repository/`, and `service/` (+ `service/impl/`) sub-packages. Cross-cutting concerns live under `common` (currently `common/config` for `SecurityConfig`, and `common/exception` for the error-handling stack). Follow this same layout when adding a new feature/domain — one package per entity from the translation table above (e.g. `content`, `comment`, `rating`, `userlist`, `diaryentry`, `like`, `follower`, `followedperson`, `top5entry`, `notification`).

**Request flow:** Controller → `Service` interface → `ServiceImpl` → `Repository` (Spring Data JPA) / `Mapper` (MapStruct) → `Entity`. DTOs are Java records; entities are Lombok-annotated JPA classes built via the builder pattern (`@Builder`, protected no-args constructor).

**Mapping (MapStruct):** Mappers are interfaces annotated `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)` — every entity/DTO field must be explicitly mapped or ignored, so adding a field to an entity or DTO requires updating the corresponding mapper or the build fails. `@AfterMapping` methods on the mapper (see `UserMapper`) apply defaults for fields not present on the incoming DTO (e.g. default `isProfilePublic`, default `profilePicture`).

**Error handling:** Domain code throws `NotFoundException` / `BadRequestException` / `ConflictException` (in `common.exception`), each annotated with `@ResponseStatus`. `GlobalExceptionHandler` (extends `ResponseEntityExceptionHandler`) turns these — plus bean-validation failures — into a consistent `ApiError` / `ValidationApiError` JSON body with timestamp, status, error, message, and path. Follow this pattern (throw a typed exception from the service layer) rather than building `ResponseEntity` error responses by hand. A catch-all `@ExceptionHandler(Exception.class)` (`handleUnexpectedException`) sits last in `GlobalExceptionHandler` as the generic last-resort case — any exception not mapped by a specific handler (a genuine bug, an unexpected exception from a dependency) still resolves to a `500` `ApiError` instead of Spring's default error body; it logs the real exception server-side (`log.error`) but only ever returns a fixed, generic message to the client, never the exception's own message or stack trace.

**Don't let Spring's default error bodies leak through.** `ResponseEntityExceptionHandler` (the class `GlobalExceptionHandler` extends) already has its own built-in handling for a fixed set of framework-level exceptions — malformed/unreadable request body (`handleHttpMessageNotReadable`), unsupported `Content-Type` (`handleHttpMediaTypeNotSupported`), a value that can't be converted to the target type such as an invalid UUID in a path variable (`handleTypeMismatch`), method-not-allowed, missing path variable, etc. If one of these isn't explicitly overridden in `GlobalExceptionHandler`, it silently falls back to Spring's own generic `ProblemDetail` response shape (`detail`/`instance`/`status`/`title`, e.g. the literal string `"Failed to read request"`) instead of this project's `ApiError` format — inconsistent with every other error response and, for `HttpMessageNotReadableException`/`HttpMediaTypeNotSupportedException`/`TypeMismatchException` specifically, strictly less informative than what's actually available on the exception. When adding a new controller (or a new field/param shape that can fail to bind — a new enum, a new typed path variable, a new `@RequestBody`), check whether it can trigger one of `ResponseEntityExceptionHandler`'s overridable `handle*` methods and, if the failure is realistically reachable by a real client (not a framework-internal edge case like `handleAsyncRequestTimeoutException` or multipart-specific handlers this API doesn't use), override it in `GlobalExceptionHandler` to return `ApiError` instead of leaving Spring's default. Extract whatever extra detail the exception actually carries — e.g. `InvalidFormatException.getTargetType().getEnumConstants()` for a bad enum value, `TypeMismatchException.getRequiredType()` for a bad path variable — the same way `handleHttpMessageNotReadable`/`handleHttpMediaTypeNotSupported`/`handleTypeMismatch` already do.

**Data-integrity conflicts:** Unique constraint violations are not pre-checked; the service layer lets the DB constraint fire, catches `DataIntegrityViolationException`, extracts the Postgres constraint name via `ConstraintViolationException`, and maps known constraint names (e.g. `uq_users_username`, `uq_users_email`) to specific `ConflictException` messages, falling back to a generic message otherwise. Constraint names are defined in the Flyway migration SQL and must stay in sync with the strings checked in the service. New tables from the translation table above (e.g. `user_lists`, `diary_entries`) should follow the same `uq_<table>_<column>` naming convention.

**Idempotent get-or-create race conditions:** For a service method that gets-or-creates a resource by a natural key (e.g. `ContentServiceImpl.getOrCreateReference`, matched by `tmdbId`+`type` or `seriesTmdbId`+`seasonNumber`+`episodeNumber`+`type`; `FollowedPersonServiceImpl.followPerson`, matched by `userId`+`personTmdbId`), don't rely on plain check-then-act (look up, then save if missing) — two concurrent calls for the same not-yet-existing resource can both miss the lookup and both attempt to insert, tripping the unique constraint. Wrap the `save` in a try/catch for `DataIntegrityViolationException`; on catch, re-run the same lookup and return the now-existing record instead of throwing (unlike the reject-on-conflict pattern above, an idempotent get-or-create must resolve to the same result regardless of which concurrent caller wins the race). Only propagate the original exception if the re-query still finds nothing — that signals a genuine unexpected DB error, not a race.

Catching `DataIntegrityViolationException` and continuing is **not safe by itself** when the get-or-create method is invoked from inside a caller that's already running its own `@Transactional` method (e.g. `ContentServiceImpl.getOrCreateReference` called from `DiaryEntryServiceImpl.createDiaryEntry`/`maybeCompleteSeason`/`maybeCompleteSeries`, all `@Transactional`). A failed `saveAndFlush` aborts the underlying database transaction immediately — on Postgres this means every subsequent statement on that same connection, including the recovery re-query, fails with "current transaction is aborted" (or, in other setups, Spring detects the transaction is rollback-only at commit and throws `UnexpectedRollbackException`) — turning the intended 200 idempotent response into a 500. It works when the get-or-create method has no caller-level ambient transaction (e.g. hit directly via `POST /contents/reference`) only because each repository call then runs in its own short-lived transaction, so a failure there can't contaminate anything else.

The fix: run the entity construction **and** the `saveAndFlush` attempt inside a separate physical transaction via `common.transaction.NewTransactionExecutor.runInNewTransaction(Supplier<T>)` (`@Transactional(propagation = Propagation.REQUIRES_NEW)`), so a constraint violation there rolls back only that isolated transaction and never touches the caller's ambient one; the recovery re-query then safely runs in the (still healthy) ambient transaction. Build the entity *inside* the same `REQUIRES_NEW` lambda whenever it references another managed entity obtained via `getReferenceById` (see `FollowedPersonServiceImpl.followPerson`) — a proxy created in the ambient session and then persisted from within a different physical transaction/session trips Hibernate's "illegally attempted to associate a proxy with two open Sessions". Apply this same pattern — `NewTransactionExecutor` plus the same-lambda entity construction rule — to any future idempotent get-or-create method backed by a DB unique constraint.

**Pagination:** List endpoints build a `PageRequest` via the shared `common.pagination.PageRequestFactory` (injected into every service that paginates — see any `*ServiceImpl` for the `pageRequestFactory.build(...)` call) rather than accepting a `Pageable` directly from the controller — this normalizes 1-based page numbers from callers into Spring's 0-based paging, applies a default/max page size, and validates inputs (throwing `BadRequestException` on invalid page number/size). `PageRequestFactory` is the single source of truth for the `DEFAULT_PAGE`/`DEFAULT_PAGE_SIZE`/`MAX_PAGE_SIZE` constants and the optional `sortBy`/`sortDirection` overload — do not re-add a per-service `buildPageRequest` method or duplicate these constants; add a new pagination behavior (a new sort rule, a different clamp) to `PageRequestFactory` itself so every service picks it up. The service layer keeps returning `Page<T>`; controllers wrap it into `PageResponseDTO<T>` (`common/dto/PageResponseDTO.java`) via `PageResponseDTO.of(page)` instead of unwrapping to `.getContent()` — every paginated list endpoint responds with an envelope (`content`, `page` — 1-based, matching what the caller sent — `size`, `totalElements`, `totalPages`, `hasNext`) rather than a bare JSON array, so clients don't have to guess whether there's a next page from the array length. This is deliberately the full `Page` shape (not `Slice`) because every paginated repository call already pays for Spring Data JPA's `COUNT` query under the hood — exposing that metadata costs nothing extra. `openapi.yaml` models this with a reusable `PageMeta` schema combined via `allOf` with an endpoint-specific `content` array on every paginated response.

**Database migrations:** Flyway migrations live in `src/main/resources/db/migration`, named `V<n>__description.sql`. `ddl-auto=validate` means Hibernate never auto-generates schema — every entity change needs a corresponding migration. Named unique constraints/indexes defined in SQL (e.g. `uq_users_username`) are relied upon by name in application code for conflict handling — keep names consistent when adding new ones.

**TMDB detail proxy:** `GET /contents/{contentId}/details` / `GET /contents/details` (batch) are the only client-facing way to fetch a `Content`'s TMDB metadata (title, overview, poster, cast, watch providers, seasons/episodes) — the client never calls the TMDB API directly, so the TMDB `api-key` (`app.tmdb.api-key`) stays server-side only. `ContentDetailsServiceImpl` resolves the `Content`'s natural key, calls one of `TmdbClient.getMovieFullDetails`/`getTvFullDetails`/`getSeasonFullDetails`/`getEpisodeFullDetails` (each one HTTP call, using TMDB's `append_to_response` to bundle `credits`/`aggregate_credits`, `watch/providers`, and `alternative_titles` instead of N separate calls), and shapes the result into `ContentDetailsDTO`. Each `TmdbClient` method calls into `TmdbClient.cachedLookup`, which reads/writes a `com.github.benmanes.caffeine.cache.Cache<String, TmdbLookupResult<X>>` bean injected directly from `TmdbCacheConfig` (TTL `app.tmdb.details-cache-ttl-hours`, one bean per method) — **not** Spring's `@Cacheable`/`CacheManager` (removed 2026-09-04, see below) — keyed by `(natural key, language)` — never by region, since TMDB returns every region in one response and the region-specific filtering (`watchProviders`, title fallback via `alternative_titles`) happens in the service layer after the cache read, so users with different `preferredRegion` but the same `preferredLanguage` share a cache entry. `SEASON`/`EPISODE` reuse the `SERIES` call (same `seriesTmdbId`, also cached) for genres/countries/regular cast/creators, mirroring how `Content.genres`/`countries` are already resolved for `EPISODE` diary entries (see Avoid section below). A failed TMDB call surfaces as `TmdbUnavailableException` → `502`, never a partial/cached-stale response. This is a stateless in-memory cache, not persistence — it does **not** relax the "never store movie/series metadata in the database" rule below; nothing from these calls is written to `Content` or any other table. See `docs/context/tmdb-proxy-design.md` for the full design.

**`TmdbClient` return type distinguishes "not found" from "unavailable":** the four `get*FullDetails` methods return `TmdbLookupResult<T>` (a sealed interface — `Found<T>`/`NotFound<T>`/`Unavailable<T>`), not `Optional<T>`. A confirmed TMDB `404` and a genuinely transient failure (timeout, `5xx`, network) used to be indistinguishable (`callWithRetry` collapsed both into `Optional.empty()`); now a `404` short-circuits without retrying (retrying a confirmed-nonexistent id is pointless) and is reported as `NotFound`, while everything else keeps the existing retry-once-then-give-up behavior and reports `Unavailable`. Most existing callers (`ContentDetailsServiceImpl`, `DiaryEntryServiceImpl`'s finale/runtime derivation) only care about "did I get data or not," so they call `.toOptional()` right after the `TmdbClient` call and keep their prior `orElseThrow(this::tmdbUnavailable)` → `502` behavior unchanged for both cases — only `ContentServiceImpl.requireFound` (below) actually branches on the distinction. `TmdbClient.cachedLookup`'s loader returns `null` (instead of the computed `Unavailable<>()`) specifically so a transient failure is never cached — Caffeine's `Cache.get(key, mappingFunction)` never stores a `null` mapping result, so `cachedLookup` translates that back into a fresh `Unavailable<>()` on every call until a real answer is cached. Any new `TmdbClient` caller must pick one of these two paths (`.toOptional()` for existing "give me the data or 502" semantics, or inspect `.isNotFound()`/`.isUnavailable()` directly when the distinction actually matters).

**`TmdbClient`'s 4 detail caches bypass Spring's `@Cacheable`/`CacheManager` entirely (2026-09-04)** — a performance audit found the 4 `get*FullDetails` methods had no cache-stampede protection (two concurrent requests for the same not-yet-cached key both hit TMDB for real instead of one waiting for the other) and proposed `sync = true` as the fix. Tested in practice: Spring rejects it at runtime with `IllegalStateException: A sync=true operation does not support the unless attribute`, since these 4 methods rely on `unless = "#result.isUnavailable()"` to implement the "never cache a transient failure" rule above — `sync` and `unless` are mutually exclusive in Spring Cache, by design (`sync` delegates straight to the cache provider's own get-or-compute, which never re-checks `unless` afterward). Fix: `TmdbCacheConfig` now exposes 4 typed `com.github.benmanes.caffeine.cache.Cache<String, TmdbLookupResult<X>>` `@Bean`s (autowired into `TmdbClient` by generic type) instead of a `CacheManager`, and `TmdbClient.cachedLookup` calls `Cache.get(key, mappingFunction)` directly — Caffeine's own get-or-compute is atomic per key (the same stampede protection `sync = true` would have given), and returning `null` from the mapping function reproduces the "never cache `Unavailable`" behavior `unless` used to provide, without needing it. `@EnableCaching` was removed (nothing else in the app used Spring's cache abstraction). `TmdbClientCachingTest` (the only test exercising the real cache, not a throwaway per-test instance) has a dedicated concurrency test proving only one real HTTP call happens under a race.

**`ContentService.getOrCreateReference` verifies TMDB existence before creating a new reference** (`ContentServiceImpl.resolveNewContentMetadata`/`requireFound`, added 2026-09-03) — runs once, right after `findExisting` comes back empty, before the create block; never runs for an already-existing reference (no repeat cost, no repeat risk, regardless of caller). `MOVIE`/`SERIES` verify the `tmdbId` itself (`getMovieFullDetails`/`getTvFullDetails`) and, from that same response, also derive `genres`/`releaseYear`/`countries`/`runtimeMinutes` (see Avoid section below) at no extra TMDB cost; `SEASON`/`EPISODE` verify the parent `seriesTmdbId` via `getTvFullDetails`. `SEASON` still verifies only the parent series, not the specific `seasonNumber` — that stays unverified, a deliberate scope boundary (`SEASON` carries none of the four derivable fields, so nothing forces a second call there). `EPISODE` closes this for its own `episodeNumber` specifically as a side effect of deriving `runtimeMinutes` (see Avoid section) whenever `trustedRuntimeMinutes = false` — an extra `getEpisodeFullDetails` call, `NotFoundException` if that episode number doesn't exist. The check uses a fixed `en-US` language (`ContentServiceImpl.EXISTENCE_CHECK_LANGUAGE`) because `getOrCreateReference` has no access to the calling user's `preferredLanguage` — none of its many callers (`DiaryEntryServiceImpl`, `UserListItemServiceImpl`, `Top5EntryServiceImpl`, `WatchlistEntryServiceImpl`, `DroppedEntryServiceImpl`, `ContentTrackingServiceImpl`, `FollowedPersonTrackingServiceImpl`, `ContentController` directly) pass one through, and only the existence status matters here, not the localized payload. `NotFound` → `NotFoundException` (`404`); `Unavailable` → `TmdbUnavailableException` (`502`), same as everywhere else TMDB is called. This closed a gap where no write path in the app ever verified a client-supplied `tmdbId`/`seriesTmdbId` corresponded to anything real — any string became a permanent, shared `Content` row.

**Security:** `SecurityConfig` wires stateless JWT authentication delivered via httpOnly cookies (`access_token`, `refresh_token`), not a Bearer header. `JwtCookieAuthenticationFilter` reads the `access_token` cookie and populates `SecurityContextHolder` before `UsernamePasswordAuthenticationFilter`; `/auth/**` and `/error` are `permitAll`, and `anyRequest().authenticated()` is the default for everything else. Refresh tokens are tracked server-side (`RefreshTokenRepository`) and rotated/revoked on `/auth/refresh` and `/auth/logout` (see `RefreshTokenServiceImpl`). CSRF protection is enabled (`CookieCsrfTokenRepository` + `SpaCsrfTokenRequestHandler`, cookie `XSRF-TOKEN` echoed back via the `X-XSRF-TOKEN` header on state-changing requests) and only ignored for `/auth/**`; the CSRF token is rotated explicitly on register/login (`AuthController.rotateCsrfToken`) instead of relying on the default per-request strategy. Because `permitAll` routes still run through `JwtCookieAuthenticationFilter`, `/auth/register` and `/auth/login` must explicitly reject requests that already carry a valid session (`AuthController.isAuthenticated()`) — Spring Security populates `SecurityContextHolder` with an `AnonymousAuthenticationToken` for anonymous requests, so check `!(authentication instanceof AnonymousAuthenticationToken)`, never just `authentication != null`. A `BCryptPasswordEncoder` bean hashes passwords on registration and verifies them on login and account deletion.

**Access policy — authenticated by default, discover is the only exception:** as of 2026-08-12, the app requires a logged-in session for everything except `/auth/**` (needed to obtain that session in the first place — register/login/refresh/logout/oauth) and `/error`. This replaced an earlier "browse without login, write requires it" policy: `GET /users` and `GET /users/{userId}` used to be `permitAll` for anonymous browsing and are now protected like everything else. There is no other `permitAll` route today. The only planned exception going forward is a future **discover** landing page (an anonymous-friendly TMDB catalog browse — trending/popular movies and series) that does not exist yet in `openapi.yaml`, `database-schema.md`, or `development-stages.md`; when it's built, it should be the explicit `permitAll` override in `SecurityConfig` (and `security: []` in `openapi.yaml`) — don't add `permitAll` anywhere else without the same explicit announce-then-ask conversation described above, since that would silently reopen anonymous access this policy change intentionally closed.

## Code conventions

- **English only.** All class names, method names, field names, DTOs, enum values, and API route paths are in English — translate on the way in from the Portuguese source schema/dev-stages docs, using the table above. Route paths must match exactly what's defined in `openapi.yaml`, since that's the public contract.
- **No code comments.** Write self-explanatory code (clear names, small methods) instead of explaining behavior in comments. Javadoc is not required unless explicitly requested.
- Follow the existing `User` domain (entity, repository, service, mapper, DTOs) as the reference implementation for structure and style when building out any new entity from the translation table.
- **Default to idiomatic Java 21 / Spring Boot best practices** wherever this file and the existing codebase don't already dictate a specific convention. Where they conflict, the project's own conventions win — this file exists precisely to override generic defaults with decisions already made for this codebase.
- **Test method naming.** Name test methods `should<ExpectedBehavior>When<Condition>` in camelCase (no underscores) — e.g. `shouldThrowNotFoundExceptionWhenIdDoesNotExist`, `shouldUpdateUsernameTrimmedWhenDifferentValueProvided`.
- **Test `@DisplayName`.** Pair every `@Test` with a `@DisplayName` in the format `"[methodUnderTest] Should <expected behavior> - When <condition>"`: the method under test in square brackets exactly as written in the source (camelCase, untouched), followed by `Should ... - When ...` with every other word capitalized (Title Case), except type/exception names which keep their real identifier casing (e.g. `ConflictException`, `UserResponseDTO`). Example: `@DisplayName("[updateUser] Should Throw ConflictException With Username Message - When Update Violates Username Constraint")`.
- **Tests follow refactors.** Whenever code is refactored (renamed, restructured, moved, signature or behavior changed), check how the existing tests covered the old implementation and update them to match — stale assertions, mocks, `@DisplayName`s, or entire test cases left over from the old shape must be fixed or removed, not left passing-but-outdated alongside the refactor.

## Test coverage baseline

This is a **minimum checklist**, not a ceiling — when a method has extra branches (authorization, extra guards, dynamic filters) beyond these generic shapes, add one test per branch on top of the baseline.

### Service unit tests

| Method shape | Baseline | What each covers |
|---|---|---|
| `getXById(id)` | 2 | Happy path (found → mapped DTO); NotFound (`Optional.empty()` → `NotFoundException`). +1 per extra authorization/visibility branch (e.g. `getUserById`'s private-profile check → `ForbiddenException`). |
| `getXsByFilter(...)` (paginated, via `PageRequestFactory`) | 2 + F + fixed pagination checklist | Happy path; empty result. +F: one test per dynamic filter/conditional that changes the query, plus one per required-filter validation (`BadRequestException` when missing/blank). Fixed checklist regardless of F (reusing `PageRequestFactory` still needs proving it's wired correctly — the arithmetic itself is covered once in `PageRequestFactoryTest`, not re-tested per service): page number null→default, zero→default, positive→page-1, negative→`BadRequestException`; page size null→default, valid, at max limit, exceeds limit→default, negative→`BadRequestException`, zero→`BadRequestException`; sort applied/not applied if the method supports it. |
| `saveNewX(dto)` | 1 + K + 2 | Happy path (maps, persists, returns DTO). +K: one test per unique constraint mapped to a specific `ConflictException` message — via the `DataIntegrityViolationException` catch, **not** a pre-check like `existsByX` (see Architecture → Data-integrity conflicts). +2 fixed: unknown constraint name → generic message; cause not a `ConstraintViolationException` (or null) → generic message. +1 per field with a normalization rule (trim/lowercase) worth asserting explicitly. |
| `updateX(id, dto)` / `patchX(id, dto)` | 2 + 1 + 2×U + K | Happy path; NotFound. +1 fixed: no field changes when all patch fields are null. +2 per updatable field (`U`): changes when a different value is provided (normalized), no-op when the same value is provided. +K: one test per field with a unique constraint, same `DataIntegrityViolationException`-catch pattern as create. +1 per genuine business rule blocking a field from changing (distinct from a conflict). |
| `deleteX(id, ...)` | 2 | Happy path; NotFound. +1 per extra guard before deletion (password confirmation, resource-ownership check — mandatory starting at `DiaryEntry`, per `development-stages.md`'s Fase 3 "autorização por dono do recurso"). |

### Controller unit tests (mocked service, no Spring context)

A plain `@ExtendWith(MockitoExtension.class)` controller test calls the controller method directly — there's no `DispatcherServlet`, no `GlobalExceptionHandler`, no Spring Security filter chain running. It **cannot** observe HTTP status translation for thrown exceptions (`NotFoundException`→404, `ConflictException`→409, `BadRequestException`→400, auth failures→401/403); that only happens inside the real MVC stack. Don't write those cases here — they'd just re-assert that a Java exception propagates, which the service test already covers, and they belong in the integration test instead.

| What to test | Baseline |
|---|---|
| Happy path per endpoint | 1 — correct status code set by the controller itself (`ResponseEntity.ok`/`.noContent`/`.created`, etc.) and correct body/delegation to the service. |
| Values the controller derives itself | +1 per such value — e.g. resolving the current user id from `SecurityContextHolder`, building a `Location` header. |
| Exception → HTTP status mapping | Not covered here — see integration tests below. |

### Controller integration tests (`@SpringBootTest` + `MockMvc` + Testcontainers)

This is where exception→status mapping, validation, real persistence, and security actually get proven end to end. Don't re-derive every service-level branch here — integration tests exist to prove what a mocked repository/service can't (real SQL, real HTTP status codes, real security filters), not to duplicate the service unit-test suite.

| Case | When it applies |
|---|---|
| Happy path | Always — real request through the real DB, asserting the response body and, for mutating endpoints, that the row was actually persisted/changed. |
| NotFound → 404 | Any method that looks up by id. |
| Conflict → 409 | Any method that can violate a unique constraint — a real duplicate insert, not a mocked exception. |
| BadRequest → 400 | Each validation rule that can fail (missing/malformed field, invalid page/size) — assert the DB is untouched afterward for mutating endpoints. |
| Unauthorized → 401 | **Mandatory for every endpoint requiring authentication** — request with no `access_token` cookie. |
| Forbidden (CSRF) → 403 | **Mandatory for every authenticated mutating endpoint** — request missing the `X-XSRF-TOKEN` header/CSRF cookie (see Architecture → Security). |
| Extra guard-specific outcomes | Anything method-specific not covered above (e.g. delete with wrong password → 401, private profile → 403). |

## Test quality

A test that passes regardless of whether the implementation is correct is worse than no test — it creates false confidence and hides regressions. Before considering any new or edited test finished, check it against these:

- **Assert real outcomes, not mock echoes.** If a test stubs `when(mock.x()).thenReturn(value)` and then only asserts the result equals `value`, it never exercised the method's actual logic — it just proved Mockito can return what it was told to return. Assert against something the code under test actually computed (a captured argument, a transformed field, a thrown exception with a specific message), not the same literal handed to the stub.
- **Never mock the unit under test.** Only mock collaborators (repository, other services, external clients). Mocking/spying the class whose behavior the test claims to verify makes the assertion meaningless.
- **Prefer specific assertions over presence checks.** `assertThat(result).isNotNull()` or a bare `assertDoesNotThrow(...)` rarely proves correctness by itself — assert the actual expected value, field, or exception type/message (see the existing `assertThatThrownBy(...).isInstanceOf(...).hasMessage(...)` pattern already used throughout this codebase).
- **Verify interactions with the arguments that matter, not just that a call happened.** `verify(mock).someMethod(any())` proves a call occurred, not that it occurred with the right data — use an `ArgumentCaptor` or an explicit matcher whenever the argument value is what the test is actually supposed to be proving (see the field-normalization tests in `UserServiceImplTest` — e.g. `shouldNormalizeEmailUsernameAndPasswordBeforeSavingWhenDataIsValid` — as the reference pattern).
- **Sanity-check a new test by breaking the code on purpose.** Before moving on, mentally (or actually, temporarily) revert or comment out the line the test is supposed to cover and confirm the test would fail. A test that stays green either way isn't testing anything — delete or fix it rather than leaving it in the suite.
- **A test must fail when production code regresses, not when a stub's setup changes.** If tightening a stub's `when(...)` (making it more specific/strict) is the only way a test would ever fail, the test isn't actually covering the behavior it claims to.

## Avoid

- Do not store movie/series/cast data in the database — only the `tmdbId` + `type` reference on `Content`.
- The deliberate exceptions to "no metadata on `Content`" — seven narrow fields (or field pairs) that let the backend do something useful without storing full TMDB records:
  1. `isSeasonFinale` (on `EPISODE` rows) and `isSeriesFinale` (accepted on `EPISODE` rows as a forward-looking hint for the season being watched, read from `SEASON` rows once one exists) — flags that let the backend detect season/series completion. **Client-supplied only at `POST /contents/reference`** (registering a reference directly, outside the diary flow) — the last genuinely client-supplied entry point for these two. `POST /diary/bulk` already derived both from TMDB independently (aired episode/season counts, `DiaryEntryServiceImpl.resolveSeasonFinaleEpisodeNumber`/`resolveSeriesFinaleSeasonNumber`), and `POST /diary` (single episode log) closed the same gap on 2026-09-03: `DiaryEntryServiceImpl.withDerivedEpisodeFinaleFlags`/`deriveSeriesFinaleFlag` reuse the same `getSeasonFullDetails`/`getTvFullDetails` calls to derive both server-side, overriding whatever the client sends whenever TMDB has aired-episode data; the client's value is only honored as a fallback when TMDB doesn't know yet (a season announced but not yet aired, or TMDB unavailable) — same fallback philosophy as bulk. Both have exactly one narrow mutation path, mirroring each other: `ContentServiceImpl.clearPreviousSeriesFinale` clears the previous finale season's `isSeriesFinale` flag when a new season is confirmed as the series' finale (a revived series moving its finale to a later season), and `ContentServiceImpl.clearPreviousSeasonFinale` does the same for `isSeasonFinale` on `EPISODE` rows when a season's finale moves to a later episode (a season airing a bonus/delayed episode after the previous finale was already logged). Both only transfer forward (new season/episode number strictly greater than the current finale's) — an equal-or-earlier attempt is rejected with `409` instead of transferring. Resending an already-registered content's reference with a different non-null value is rejected with `409`, never silently overwritten (`ContentServiceImpl.assertNoMetadataMismatch`) — a client sending `null` never conflicts, and a `null`-vs-`null`-already-persisted value is backfilled instead of rejected (`ContentServiceImpl.reconcileExisting`).
  2. `runtimeMinutes` (on `MOVIE`/`EPISODE` rows only — a `SEASON`/`SERIES` has no single *per-item* runtime of its own, see exception 6 below for the whole-series aggregate) — feeds `UserResponseDTO`/`PublicUserProfileDTO`'s `totalMinutesWatched`/`minutesWatchedLast30Days` (`DiaryEntryRepository.sumRuntimeMinutesByUserId*`, only summing `MOVIE`/`EPISODE` diary entries — `SEASON`/`SERIES` are synthetic completion markers, see `DiaryEntryServiceImpl.maybeCompleteSeason`/`maybeCompleteSeries`, and would double-count). **Server-derived from TMDB since 2026-09-03** (see below) — never accepted from a client, `400` if sent.
  3. `genres` (on `MOVIE`/`SERIES` rows only — genres are a whole-title TMDB property, not per-episode) — feeds `genreCounts` (`UserResponseDTO`/`PublicUserProfileDTO`): how many distinct `MOVIE`/`SERIES` titles the user watched per genre, all-time, deduped so a rewatch or multiple episodes of the same series count once. Resolving an `EPISODE` diary entry by genre reads genres from the `SERIES`-type `Content` sharing the same `seriesTmdbId`, since the episode's own `Content` row never carries `genres`. **Server-derived from TMDB since 2026-09-03.**
  4. `releaseYear` (on `MOVIE`/`SERIES` rows only, same whole-title restriction as `genres`) — feeds decade-bucketed watch stats (Month/Year in Review, All Time Stats screens, see `docs/context/telas.md`). Resolved for `EPISODE` diary entries the same way as `genres`, via the `SERIES`-type `Content` sharing the same `seriesTmdbId`. **Server-derived from TMDB since 2026-09-03.**
  5. `countries` (on `MOVIE`/`SERIES` rows only, same whole-title restriction as `genres`) — ISO 3166-1 alpha-2 codes, feeds watch-count-by-country stats on the same screens as `releaseYear`. Same `EPISODE` resolution path via `seriesTmdbId`. Normalized (trim + uppercase + alphabetical sort) before saving, same reasoning as `genres`' normalization (`ContentServiceImpl.normalizeCountries`). **Server-derived from TMDB since 2026-09-03.**
  6. `totalRuntimeMinutes`/`runtimeMinutesEpisodeCount` (on `SERIES` rows only, added 2026-09-05) — the whole-series aggregate runtime, distinct in kind from every other exception above: those are write-once/immutable (verified against TMDB, then frozen); this one legitimately *grows over time* (a new episode airing increases the total), so it deliberately sits **outside** `ContentRefCreationDTO`/`validate`/`assertNoMetadataMismatch`/`reconcileExisting` entirely — never client-supplied, never even accepted as a concept there, maintained only by `ContentDetailsServiceImpl` (initial computation + best-effort persistence, `persistRuntimeAggregate`) and `ContentTrackingServiceImpl` (incremental `+=` on a detected `NEW_EPISODE`, full recomputation the moment the series transitions to `Ended`/`Canceled` to correct any drift before treating the value as final). `runtimeMinutesEpisodeCount` is the number of episodes that contributed a non-null runtime to the sum; the average shown in `ContentDetailsDTO.runtimeMinutes` is always `round(total / count)` computed at read time, never stored separately. Once a series is `Ended`/`Canceled` and a baseline already exists, `ContentDetailsServiceImpl.buildSeriesDetails` skips fetching every season and reads the stored aggregate instead — see `docs/context/business-rules.md` § Content for the full skip/increment/reconciliation/revival design.
  Separately, `TrackedContentState`/`TrackedPersonState`/`TrackedPersonCredit` (added 2026-08-29) are a distinct, narrow tracking-only cache — not part of `Content` at all, never exposed via any API endpoint — that exists purely so `ContentTrackingJob` and `FollowedPersonTrackingJob` (the two `@Scheduled` jobs backing the `Notification` feature) can detect day-over-day TMDB diffs (release date, status, next episode, a followed person's credits) without re-deriving prior state from TMDB on every run. Since 2026-09-05, `TrackedContentState` also gates the daily job itself: content whose `lastKnownStatus` is already terminal (`Released`/`Canceled` for `MOVIE`, `Ended`/`Canceled` for `SERIES`) is skipped entirely (no TMDB call) rather than rechecked every run — a revival is only ever noticed lazily, via `ContentDetailsServiceImpl` observing a fresh non-terminal status and calling `ContentTrackingService.reactivateAfterRevival`, not by the job itself.

  **`runtimeMinutes`/`genres`/`releaseYear`/`countries` stopped being client-supplied entirely on 2026-09-03** (`ContentServiceImpl.validate` rejects a non-null value for any of them with `400`, for every `type`) — closing a gap where any authenticated user could write an incorrect value into a shared, cross-user `Content` row, permanently and for everyone who ever references that same `tmdbId` afterward (the 409-on-mismatch conflict rule protected against a *second* wrong submission overwriting a first, but never protected against the *first* submission itself being wrong — there was never any server-side verification). Two implementation paths, chosen for cost, both driven by `ContentServiceImpl` (`resolveNewContentMetadata` for a brand-new `Content`, hard-failing `404`/`502` same as before; `backfillMissingTmdbMetadata` for an existing one missing the field, best-effort, swallowing TMDB failure):
  - **`MOVIE`/`SERIES`**: `genres`/`releaseYear`/`countries` (and `runtimeMinutes`, `MOVIE` only) are extracted from the very same `getMovieFullDetails`/`getTvFullDetails` response `ContentServiceImpl` already calls to verify the `tmdbId` exists on TMDB (see the existence-check paragraph below) — zero additional TMDB calls versus what already existed.
  - **`EPISODE`**: `runtimeMinutes` only. `ContentService.getOrCreateReference(dto, trustedRuntimeMinutes)` — the plain 1-arg `getOrCreateReference(dto)` used by most callers still delegates with `trustedRuntimeMinutes = false` — lets a caller that already independently obtained a TMDB-verified value hand it straight through (`trustedRuntimeMinutes = true`, no extra call). `DiaryEntryServiceImpl.bulkLogSeason`/`bulkLogSeries` (`POST /diary/bulk`, both `SEASON` and `SERIES` requests) are the only two callers that pass `true` — both already fetch that season's `TmdbSeasonFullDetails` for other reasons (finale derivation, `watchedDate` validation), so reading `runtime` off episodes already in hand costs nothing extra (`episodeRuntimeMinutesFromTmdb`). `DiaryEntryBulkCreationDTO.episodeRuntimeMinutes` (the old client-supplied per-episode map for a `SEASON` request) was removed from the request DTO entirely — this reopens and finalizes a decision that had briefly reverted to client-supplied on 2026-09-02 for cost reasons; the reversal turned out to be unnecessary once it was clear the season data was already being fetched regardless. Every other caller — `POST /contents/reference` direct, a single-entry `POST /diary` — passes `trustedRuntimeMinutes = false`, and `ContentServiceImpl` makes one new `TmdbClient.getEpisodeFullDetails` call (cached, so it only costs a real HTTP request the first time any user ever references that specific episode) to derive the value itself. As a side effect this also verifies the specific episode number actually exists on TMDB — closing part of the scope boundary noted below (only the season/episode *number* verification remains genuinely unaddressed, and only for `SEASON`, which carries no `runtimeMinutes` field to trigger this path at all).
  - None of the four is guaranteed to end up populated — if TMDB itself has no data for it (rare), the field simply stays `null` and that `Content` keeps contributing `0` to the corresponding aggregation, same behavior as before, just always TMDB-sourced now instead of client-sourced.

  See `docs/context/business-rules.md` §§ Content, DiaryEntry, User.
- Do not skip phases in the build order above.
- Do not add an endpoint without a matching entry in `openapi.yaml`, or a table without a matching entry in the logical model — update the docs in `docs/context/` alongside code changes.
- Do not add code comments; prefer clear naming and small, well-named methods.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
