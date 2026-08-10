# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Watchwise API is a Spring Boot 4.1 / Java 21 REST backend, built with Maven. It uses PostgreSQL (via Testcontainers/Docker Compose in dev), Flyway migrations, Spring Data JPA, Spring Security (stateless JWT authentication delivered via httpOnly cookies, with CSRF protection — see Architecture below), MapStruct for entity/DTO mapping, and Lombok. The codebase is early-stage: only the `user` domain is implemented so far (entity, repository, service, mapper, DTOs), plus its supporting `auth` refresh-token piece. `AuthController` (`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`) and `UserController` (`/users`, `/users/me`, `/users/{userId}`) are the only controllers built so far.

Watchwise is a social app for tracking, rating, and commenting on movies, series, seasons, and episodes. Movies, series, cast, and awards are **never stored in the database** — that data always comes from the TMDB API. The backend only stores a lightweight reference on `Content` (`type` plus whichever ID fields that `type` needs — see below), used as an internal key to link a user's interactions (comments, ratings, diary entries, lists, top5, etc) to a piece of media.

TMDB has no flat by-ID lookup for seasons or episodes (unlike `/movie/{id}` and `/tv/{id}`) — fetching one requires the composite path `/tv/{seriesId}/season/{seasonNumber}` or `/tv/{seriesId}/season/{seasonNumber}/episode/{episodeNumber}`. A season's or episode's own TMDB `id` exists in the TMDB response but isn't usable for lookup by itself, so `Content` doesn't store it. Instead: `type = MOVIE`/`SERIES` rows carry `tmdbId` (their own TMDB id, sufficient alone); `type = SEASON`/`EPISODE` rows carry `seriesTmdbId` + `seasonNumber` (and `episodeNumber` for `EPISODE`) instead of `tmdbId`. All of these are nullable columns, populated only for the types that need them — see `docs/context/database-schema.html` and `openapi.yaml`'s `ContentRefCreation`.

Note: `UserList` (below) is a user-created custom list (e.g. "Best sci-fi of the 90s"). It is distinct from the planned **watchlist** feature (what a user wants to watch next) — that's a separate concept not yet in the logical model. Don't conflate the two, and don't reuse the `WatchList` name for `UserList`.

## Domain context (read before modeling new entities or endpoints)

- **Logical database model**: `docs/context/database-schema.md` — full ER diagram (entities, PKs, FKs, column types). Entity names there are in Portuguese (source spec); see translation table below for the English names to use in code.
- **API contract**: `docs/context/openapi.yaml` — every endpoint, request/response schema, tags, and auth rules. Route paths must be implemented exactly as specified in the spec.
- **Build order**: `docs/context/development-stages.md` — required implementation phases (see below).

If an endpoint in the OpenAPI spec has no corresponding entity in the schema, or vice versa, stop and flag it rather than inventing columns or routes.

When judging or answering whether a feature/endpoint/parameter "makes sense" to have, ground the answer in this app's own documented scope (this file, `docs/context/openapi.yaml`, `docs/context/development-stages.md`) instead of reasoning from what's common practice in other apps. A pattern being common elsewhere doesn't mean it applies here — check whether Watchwise's own domain and build order actually call for it before recommending it.

### Entity naming: schema (PT) → code (EN)

The logical model and dev-stages doc use Portuguese entity names; all Java code (classes, fields, DTOs, enums) must be in English. Use this mapping consistently:

| Schema (PT)   | Code (EN)       | Notes |
|---------------|------------------|-------|
| USUARIO       | `User`           | already implemented |
| CONTEUDO      | `Content`        | type reference; MOVIE/SERIES use tmdbId, SEASON/EPISODE use seriesTmdbId + seasonNumber (+ episodeNumber) instead |
| COMENTARIO    | `Comment`        | |
| AVALIACAO     | `Rating`         | numeric score on a `Content` |
| LISTA         | `UserList`       | user-created custom list; avoid bare `List` — collides with `java.util.List` |
| ITEM_LISTA    | `UserListItem`   | belongs to a `UserList` |
| LOG           | `DiaryEntry`     | maps to `/diario` endpoints; optional FK to `Comment`/`Rating` |
| CURTIDA       | `Like`           | targets either a `Comment` or a `DiaryEntry`, never both |
| SEGUIDOR      | `Follower`       | user-follows-user |
| SEGUE_PESSOA  | `FollowedPerson` | user follows a TMDB person (actor/director), not a `User` |
| TOP5          | `Top5Entry`      | |
| NOTIFICACAO   | `Notification`   | |

Field names follow the same English-translation rule (e.g. `contaPublica` in the schema/OpenAPI → `isPublicAccount` in Java, matching the existing `isProfilePublic`-style boolean naming already used on `User`).

### Build order (from `development-stages.md`)

Follow this order strictly — never implement an entity whose FK points at something that doesn't exist yet. Each entity follows the existing layered flow: `Entity → Repository → Service → ServiceImpl → tests → Mapper → DTOs → Controller` (see `user`/`auth` for the reference implementation of every layer, controllers included).

1. **Foundation (no FK deps)**: `User` (done), `Content`
2. **Depend only on User**: `Follower`, `FollowedPerson`
3. **Depend on User + Content**: `Comment`, `Rating`, `Top5Entry`
4. **Depend on User (+ Content via items)**: `UserList`, then `UserListItem`
5. **Depend on User + Content + Comment + Rating**: `DiaryEntry`
6. **Depend on User + Comment + DiaryEntry**: `Like`
7. **Satellite**: `Notification`
8. **Aggregations, no new entity**: `Summary` service (aggregates `DiaryEntry` + `Content`), `Search` service (aggregates `UserList`, local `User`, + TMDB proxy)

### Endpoint groups (see `openapi.yaml` for full contract)

- **Auth**: `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` (all done), `/auth/oauth/{provider}` (not yet implemented)
- **Users**: `/users`, `/users/me`, `/users/{id}`, `/followers`, `/following`, `/follow`, `/follow-people/{personTmdbId}`, `/top5`, `/summary`
- **Content**: `/contents/reference`, `/contents/{id}/comments`, `/contents/{id}/ratings`
- **Comments**: `/comments/{id}`, `/comments/{id}/like`
- **Ratings**: `/ratings/{id}`
- **Lists**: `/users/{id}/lists`, `/lists/{id}`, `/lists/{id}/items`, `/lists/{id}/items/{itemId}`
- **Diary**: `/users/{id}/diary`, `/diary`, `/diary/{id}`, `/diary/{id}/like`
- **Notifications**: `/notifications`, `/notifications/{id}/read`
- **Search**: `/search`

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

Whenever a large enough change is made to the code, commit it and push to the remote — don't wait to be asked. Judge for yourself whether a change is large enough to warrant its own commit (e.g. a finished feature/etapa, a bug fix, a completed refactor) versus something too small or still in-progress to commit on its own. Split unrelated concerns into separate commits rather than bundling them, matching the granularity already used in this repo's history.

## Communication style

When explaining something and a technical term comes up, add a simpler explanation in parentheses right after it.

## Before changing an implementation

Before changing how something is implemented, work through a short, honest self-assessment covering:

- Whether the change is actually necessary, or whether the underlying problem could be solved without it (or without as much of it).
- Whether it would introduce any security risk, vulnerability, or gap — including subtle ones (auth/authorization bypass, broken invariants, information leaks), not just the obvious case being addressed.
- Whether the way it's about to be implemented is genuinely the best approach available, not just the first idea that would work — name the trade-off if a simpler or more robust alternative exists and isn't being used.

Be direct about weaknesses and trade-offs instead of defaulting to a confident "this is the way to do it" assumption.

Also check whether other parts of the codebase implement the same or similar logic and whether this change makes sense there too. If it does, apply it there as well instead of fixing only the spot that was pointed out — don't leave the same bug/inconsistency behind in a sibling implementation just because it wasn't explicitly named.

## Architecture

**Feature-package structure.** Code lives under `com.watchwise.watchwise_api.<feature>`, with each feature split into `dto/`, `entity/`, `mapper/`, `repository/`, and `service/` (+ `service/impl/`) sub-packages. Cross-cutting concerns live under `common` (currently `common/config` for `SecurityConfig`, and `common/exception` for the error-handling stack). Follow this same layout when adding a new feature/domain — one package per entity from the translation table above (e.g. `content`, `comment`, `rating`, `userlist`, `diaryentry`, `like`, `follower`, `followedperson`, `top5entry`, `notification`).

**Request flow:** Controller → `Service` interface → `ServiceImpl` → `Repository` (Spring Data JPA) / `Mapper` (MapStruct) → `Entity`. DTOs are Java records; entities are Lombok-annotated JPA classes built via the builder pattern (`@Builder`, protected no-args constructor).

**Mapping (MapStruct):** Mappers are interfaces annotated `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)` — every entity/DTO field must be explicitly mapped or ignored, so adding a field to an entity or DTO requires updating the corresponding mapper or the build fails. `@AfterMapping` methods on the mapper (see `UserMapper`) apply defaults for fields not present on the incoming DTO (e.g. default `isProfilePublic`, default `profilePicture`).

**Error handling:** Domain code throws `NotFoundException` / `BadRequestException` / `ConflictException` (in `common.exception`), each annotated with `@ResponseStatus`. `GlobalExceptionHandler` (extends `ResponseEntityExceptionHandler`) turns these — plus bean-validation failures — into a consistent `ApiError` / `ValidationApiError` JSON body with timestamp, status, error, message, and path. Follow this pattern (throw a typed exception from the service layer) rather than building `ResponseEntity` error responses by hand.

**Don't let Spring's default error bodies leak through.** `ResponseEntityExceptionHandler` (the class `GlobalExceptionHandler` extends) already has its own built-in handling for a fixed set of framework-level exceptions — malformed/unreadable request body (`handleHttpMessageNotReadable`), unsupported `Content-Type` (`handleHttpMediaTypeNotSupported`), a value that can't be converted to the target type such as an invalid UUID in a path variable (`handleTypeMismatch`), method-not-allowed, missing path variable, etc. If one of these isn't explicitly overridden in `GlobalExceptionHandler`, it silently falls back to Spring's own generic `ProblemDetail` response shape (`detail`/`instance`/`status`/`title`, e.g. the literal string `"Failed to read request"`) instead of this project's `ApiError` format — inconsistent with every other error response and, for `HttpMessageNotReadableException`/`HttpMediaTypeNotSupportedException`/`TypeMismatchException` specifically, strictly less informative than what's actually available on the exception. When adding a new controller (or a new field/param shape that can fail to bind — a new enum, a new typed path variable, a new `@RequestBody`), check whether it can trigger one of `ResponseEntityExceptionHandler`'s overridable `handle*` methods and, if the failure is realistically reachable by a real client (not a framework-internal edge case like `handleAsyncRequestTimeoutException` or multipart-specific handlers this API doesn't use), override it in `GlobalExceptionHandler` to return `ApiError` instead of leaving Spring's default. Extract whatever extra detail the exception actually carries — e.g. `InvalidFormatException.getTargetType().getEnumConstants()` for a bad enum value, `TypeMismatchException.getRequiredType()` for a bad path variable — the same way `handleHttpMessageNotReadable`/`handleHttpMediaTypeNotSupported`/`handleTypeMismatch` already do.

**Data-integrity conflicts:** Unique constraint violations are not pre-checked; the service layer lets the DB constraint fire, catches `DataIntegrityViolationException`, extracts the Postgres constraint name via `ConstraintViolationException`, and maps known constraint names (e.g. `uq_users_username`, `uq_users_email`) to specific `ConflictException` messages, falling back to a generic message otherwise. Constraint names are defined in the Flyway migration SQL and must stay in sync with the strings checked in the service. New tables from the translation table above (e.g. `user_lists`, `diary_entries`) should follow the same `uq_<table>_<column>` naming convention.

**Idempotent get-or-create race conditions:** For a service method that gets-or-creates a resource by a natural key (e.g. `ContentServiceImpl.getOrCreateReference`, matched by `tmdbId`+`type` or `seriesTmdbId`+`seasonNumber`+`episodeNumber`+`type`), don't rely on plain check-then-act (look up, then save if missing) — two concurrent calls for the same not-yet-existing resource can both miss the lookup and both attempt to insert, tripping the unique constraint. Wrap the `save` in a try/catch for `DataIntegrityViolationException`; on catch, re-run the same lookup and return the now-existing record instead of throwing (unlike the reject-on-conflict pattern above, an idempotent get-or-create must resolve to the same result regardless of which concurrent caller wins the race). Only propagate the original exception if the re-query still finds nothing — that signals a genuine unexpected DB error, not a race. Apply this same pattern to any future idempotent get-or-create method backed by a DB unique constraint.

**Pagination:** List endpoints build a `PageRequest` manually via a service-level helper (see `UserServiceImpl.buildPageRequest`) rather than accepting a `Pageable` directly from the controller — this normalizes 1-based page numbers from callers into Spring's 0-based paging, applies a default/max page size, and validates inputs (throwing `BadRequestException` on invalid page number/size).

**Database migrations:** Flyway migrations live in `src/main/resources/db/migration`, named `V<n>__description.sql`. `ddl-auto=validate` means Hibernate never auto-generates schema — every entity change needs a corresponding migration. Named unique constraints/indexes defined in SQL (e.g. `uq_users_username`) are relied upon by name in application code for conflict handling — keep names consistent when adding new ones.

**Security:** `SecurityConfig` wires stateless JWT authentication delivered via httpOnly cookies (`access_token`, `refresh_token`), not a Bearer header. `JwtCookieAuthenticationFilter` reads the `access_token` cookie and populates `SecurityContextHolder` before `UsernamePasswordAuthenticationFilter`; `/auth/**` and `/error` are `permitAll`, and `anyRequest().authenticated()` is the default for everything else. Refresh tokens are tracked server-side (`RefreshTokenRepository`) and rotated/revoked on `/auth/refresh` and `/auth/logout` (see `RefreshTokenServiceImpl`). CSRF protection is enabled (`CookieCsrfTokenRepository` + `SpaCsrfTokenRequestHandler`, cookie `XSRF-TOKEN` echoed back via the `X-XSRF-TOKEN` header on state-changing requests) and only ignored for `/auth/**`; the CSRF token is rotated explicitly on register/login (`AuthController.rotateCsrfToken`) instead of relying on the default per-request strategy. Because `permitAll` routes still run through `JwtCookieAuthenticationFilter`, `/auth/register` and `/auth/login` must explicitly reject requests that already carry a valid session (`AuthController.isAuthenticated()`) — Spring Security populates `SecurityContextHolder` with an `AnonymousAuthenticationToken` for anonymous requests, so check `!(authentication instanceof AnonymousAuthenticationToken)`, never just `authentication != null`. A `BCryptPasswordEncoder` bean hashes passwords on registration and verifies them on login and account deletion.

**Access policy — browse without login, write requires it:** pure read/browse/search endpoints (`GET /users`, `GET /users/{userId}`, and — once built — content search, comment/rating listings, `/search`) are explicit `permitAll` overrides in `SecurityConfig` (and `security: []` in `openapi.yaml`), the same pattern many consumer apps use for anonymous browsing. Anything that mutates state (register aside, which is how you get an account in the first place) requires authentication, and so does any read that's inherently personal or not yet privacy-modeled (`/users/me`, notifications, diary, own lists/summary, followers/following) — when adding a new browse-style GET endpoint from a later build phase, default to protected and only open it up once its privacy rules (if any) are actually decided, following the same reasoning applied to `/users` and `/users/{userId}`. When adding a `permitAll` route matcher, order it so more specific paths (like `/users/me`) are declared before broader patterns (like `/users/{userId}`) that would otherwise match them first — Spring Security uses the first matching rule.

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
| `getXsByFilter(...)` (paginated, via `buildPageRequest`) | 2 + F + fixed pagination checklist | Happy path; empty result. +F: one test per dynamic filter/conditional that changes the query, plus one per required-filter validation (`BadRequestException` when missing/blank). Fixed checklist regardless of F (reusing `buildPageRequest` still needs proving it's wired correctly): page number null→default, zero→default, positive→page-1, negative→`BadRequestException`; page size null→default, valid, at max limit, exceeds limit→default, negative→`BadRequestException`, zero→`BadRequestException`; sort applied/not applied if the method supports it. |
| `saveNewX(dto)` | 1 + K + 2 | Happy path (maps, persists, returns DTO). +K: one test per unique constraint mapped to a specific `ConflictException` message — via the `DataIntegrityViolationException` catch, **not** a pre-check like `existsByX` (see Architecture → Data-integrity conflicts). +2 fixed: unknown constraint name → generic message; cause not a `ConstraintViolationException` (or null) → generic message. +1 per field with a normalization rule (trim/lowercase) worth asserting explicitly. |
| `updateX(id, dto)` / `patchX(id, dto)` | 2 + 1 + 2×U + K | Happy path; NotFound. +1 fixed: no field changes when all patch fields are null. +2 per updatable field (`U`): changes when a different value is provided (normalized), no-op when the same value is provided. +K: one test per field with a unique constraint, same `DataIntegrityViolationException`-catch pattern as create. +1 per genuine business rule blocking a field from changing (distinct from a conflict). |
| `deleteX(id, ...)` | 2 | Happy path; NotFound. +1 per extra guard before deletion (password confirmation, resource-ownership check — mandatory starting at Comment/Rating, per `development-stages.md`'s Fase 3 "autorização por dono do recurso"). |

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

## Avoid

- Do not store movie/series/cast data in the database — only the `tmdbId` + `type` reference on `Content`.
- Do not skip phases in the build order above.
- Do not add an endpoint without a matching entry in `openapi.yaml`, or a table without a matching entry in the logical model — update the docs in `docs/context/` alongside code changes.
- Do not add code comments; prefer clear naming and small, well-named methods.
