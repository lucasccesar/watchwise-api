# Notification (Fase 7) — TMDB Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `Notification` feature end to end — a TMDB polling layer that detects six event types (release, announced date, cancelled, renewed, new episode, new credit from a followed person) and delivers them as `Notification` rows to affected users, without ever calling TMDB more than once per distinct tracked title/person per cycle.

**Architecture:** Two `@Scheduled` jobs (`ContentTrackingJob` daily, `FollowedPersonTrackingJob` weekly) poll TMDB through a new `common/tmdb/TmdbClient` (Spring `RestClient`, no new dependency), diff the response against small internal cache tables (`TrackedContentState`, `TrackedPersonState`/`TrackedPersonCredit`) via a pure `ContentChangeDetector`, and fan out one `Notification` row per affected user. Full spec: `docs/superpowers/specs/2026-08-29-notification-tmdb-tracking-design.md`.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA, Flyway, MapStruct, Jackson 3 (`tools.jackson.databind`, annotations still `com.fasterxml.jackson.annotation`), JUnit 5 + Mockito + AssertJ, Testcontainers (`postgres:16-alpine`), `MockRestServiceServer`.

**Build-order source:** `docs/context/development-stages.md`'s `## Fase 7 — Satélite — depende de Usuario + Conteudo` (line 142) is the authoritative requirement this plan implements:

> | Entidade | Sequência |
> |---|---|
> | **Notificacao** | entity → repository → service → service test → controller (`/notificacoes`) → controller test |

Mapped onto this plan's tasks: entity → Task 4 (`Notification` entity + migration); repository → Task 4 (`NotificationRepository`); service → Task 6 (`NotificationService`/`NotificationServiceImpl`); service test → Task 6 (`NotificationServiceImplTest`); controller → Task 6 (`NotificationController`, routes `GET /notifications` and `PATCH /notifications/{id}/read` — English per `openapi.yaml`, which is this project's authoritative route contract per `CLAUDE.md`; `development-stages.md`'s `/notificacoes` is the Portuguese source-doc shorthand, not the literal path to implement); controller test → Task 6 (`NotificationControllerTest` unit + `NotificationControllerIntegrationTest`). Tasks 1-3, 5, 7-8 (TMDB client, cache tables, diff detector, the two scheduled jobs) are this plan's own decomposition of what "entity/repository/service" has to actually *do* for `Notification` to be more than an inert table — `development-stages.md` names the layers, not the TMDB-polling mechanism, which is this plan's original contribution (see the design spec for why). This is forward-looking work: as of this plan being written, none of it is implemented yet, and `development-stages.md` should **not** be marked done (no `✅`) until the branch this plan produces is actually merged.

## Global Constraints

- No new Maven dependency — `RestClient` and `MockRestServiceServer` are already available via `spring-boot-starter-web`/`spring-boot-starter-test`.
- Follow the feature-package layout: `com.watchwise.watchwise_api.<feature>` with `entity/`, `repository/`, `service/` (+ `service/impl/`), `mapper/`, `dto/`, `controller/`. `TmdbClient` lives under `common/tmdb` (cross-cutting).
- Entities: `@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor`, `UUID` PK via `@GeneratedValue(strategy = GenerationType.UUID)`, explicit `@Setter` per field (see `Content`, `FollowedPerson`).
- Mappers: `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)` — every field must be mapped or explicitly ignored.
- Exceptions: throw `NotFoundException`/`BadRequestException`/`ConflictException`/`ForbiddenException` from `common.exception` — never build error `ResponseEntity`s by hand.
- Pagination: build `PageRequest` via injected `PageRequestFactory.build(pageNumber, pageSize)`; controllers wrap `Page<T>` in `PageResponseDTO.of(page)`.
- Get-or-create / idempotent writes backed by a unique constraint: wrap the write in `NewTransactionExecutor.runInNewTransaction(...)`, catch `DataIntegrityViolationException`, re-query — see `FollowedPersonServiceImpl.followPerson`.
- Migrations: `src/main/resources/db/migration/V<n>__description.sql`, next free number is **V38**.
- Test naming: `should<ExpectedBehavior>When<Condition>`; `@DisplayName("[methodUnderTest] Should <Behavior> - When <Condition>")`.
- Every `@Scheduled` job is a `@Component` reading its cron from an `app.*.cron` property in `application-dev.properties` (see `RefreshTokenCleanupJob`, `RateLimiterCleanupJob`).
- All new endpoints require authentication (default `anyRequest().authenticated()` — no `SecurityConfig` change needed).

---

## Task 1: TMDB client — config, DTOs, `TmdbClient`

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbMovieDetails.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbTvDetails.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbNextEpisode.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbCredit.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbPersonCredits.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClient.java`
- Create: `src/main/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientConfig.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/common/tmdb/TmdbClientTest.java`

**Interfaces:**
- Produces: `record TmdbMovieDetails(String id, String releaseDate, String status)`; `record TmdbTvDetails(String id, String status, TmdbNextEpisode nextEpisodeToAir)`; `record TmdbNextEpisode(String airDate, Integer seasonNumber, Integer episodeNumber)`; `record TmdbCredit(String id, String mediaType)`; `record TmdbPersonCredits(List<TmdbCredit> cast, List<TmdbCredit> crew)`; `TmdbClient.getMovieDetails(String tmdbId): Optional<TmdbMovieDetails>`, `TmdbClient.getTvDetails(String tmdbId): Optional<TmdbTvDetails>`, `TmdbClient.getPersonCombinedCredits(String personTmdbId): TmdbPersonCredits`.

- [ ] **Step 1: Write the DTO records**

```java
// TmdbMovieDetails.java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieDetails(
        String id,
        @JsonProperty("release_date") String releaseDate,
        String status) {
}
```

```java
// TmdbNextEpisode.java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbNextEpisode(
        @JsonProperty("air_date") String airDate,
        @JsonProperty("season_number") Integer seasonNumber,
        @JsonProperty("episode_number") Integer episodeNumber) {
}
```

```java
// TmdbTvDetails.java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvDetails(
        String id,
        String status,
        @JsonProperty("next_episode_to_air") TmdbNextEpisode nextEpisodeToAir) {
}
```

```java
// TmdbCredit.java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbCredit(
        String id,
        @JsonProperty("media_type") String mediaType) {
}
```

```java
// TmdbPersonCredits.java
package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonCredits(List<TmdbCredit> cast, List<TmdbCredit> crew) {
}
```

- [ ] **Step 2: Add TMDB properties**

Append to `application-dev.properties`:

```properties
app.tmdb.base-url=https://api.themoviedb.org/3
app.tmdb.api-key=
app.tmdb.timeout-ms=5000
```

Append the same three lines to `application-prod.properties` (leave `app.tmdb.api-key=` blank there too — it is supplied via the `APP_TMDB_API_KEY` environment variable in the real prod environment, same convention as other prod secrets in that file).

- [ ] **Step 3: Write `TmdbClientConfig`**

```java
package com.watchwise.watchwise_api.common.tmdb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class TmdbClientConfig {

    @Bean
    public RestClient tmdbRestClient(
            @Value("${app.tmdb.base-url}") String baseUrl,
            @Value("${app.tmdb.api-key}") String apiKey,
            @Value("${app.tmdb.timeout-ms}") long timeoutMs) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofMillis(timeoutMs))
                        .withReadTimeout(Duration.ofMillis(timeoutMs)));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}
```

*Note for the implementer:* if `ClientHttpRequestFactoryBuilder`/`ClientHttpRequestFactorySettings` don't match this exact Spring Boot 4.1 API when you compile, check the actual package under `org.springframework.boot.http.client` in the installed `spring-boot` jar (`~/.m2/repository/org/springframework/boot/spring-boot/4.1.0/`) — this corner of Spring Boot 4's HTTP client bootstrapping is new enough that exact class names are worth confirming against the real jar before trusting this snippet verbatim.

- [ ] **Step 4: Write `TmdbClient` with retry-once + timeout-skip behavior**

```java
package com.watchwise.watchwise_api.common.tmdb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbClient {

    private final RestClient tmdbRestClient;

    public Optional<TmdbMovieDetails> getMovieDetails(String tmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/movie/{id}", tmdbId)
                        .retrieve()
                        .body(TmdbMovieDetails.class),
                "movie " + tmdbId);
    }

    public Optional<TmdbTvDetails> getTvDetails(String tmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/tv/{id}", tmdbId)
                        .retrieve()
                        .body(TmdbTvDetails.class),
                "tv " + tmdbId);
    }

    public Optional<TmdbPersonCredits> getPersonCombinedCredits(String personTmdbId) {
        return callWithRetry(() -> tmdbRestClient.get()
                        .uri("/person/{id}/combined_credits", personTmdbId)
                        .retrieve()
                        .body(TmdbPersonCredits.class),
                "person " + personTmdbId);
    }

    private <T> Optional<T> callWithRetry(java.util.function.Supplier<T> call, String description) {
        try {
            return Optional.ofNullable(call.get());
        } catch (RestClientException firstFailure) {
            log.warn("TMDB call failed for {}, retrying once: {}", description, firstFailure.getMessage());
            try {
                return Optional.ofNullable(call.get());
            } catch (RestClientException secondFailure) {
                log.warn("TMDB call failed for {} after retry, skipping this cycle: {}", description, secondFailure.getMessage());
                return Optional.empty();
            }
        }
    }
}
```

- [ ] **Step 5: Write the failing test for `TmdbClient` using `MockRestServiceServer`**

```java
package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TmdbClientTest {

    private MockRestServiceServer mockServer;
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.themoviedb.org/3");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        tmdbClient = new TmdbClient(builder.build());
    }

    @Test
    @DisplayName("[getMovieDetails] Should Return Parsed Details - When TMDB Responds With A Released Movie")
    void shouldReturnParsedDetailsWhenTmdbRespondsWithAReleasedMovie() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603"))
                .andRespond(withSuccess("""
                        {"id": 603, "release_date": "1999-03-31", "status": "Released"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieDetails> result = tmdbClient.getMovieDetails("603");

        assertThat(result).isPresent();
        assertThat(result.get().releaseDate()).isEqualTo("1999-03-31");
        assertThat(result.get().status()).isEqualTo("Released");
    }

    @Test
    @DisplayName("[getMovieDetails] Should Return Empty - When TMDB Fails Twice In A Row")
    void shouldReturnEmptyWhenTmdbFailsTwiceInARow() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603")).andRespond(withServerError());
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603")).andRespond(withServerError());

        Optional<TmdbMovieDetails> result = tmdbClient.getMovieDetails("603");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[getMovieDetails] Should Retry Once And Succeed - When First Call Fails")
    void shouldRetryOnceAndSucceedWhenFirstCallFails() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603")).andRespond(withServerError());
        mockServer.expect(requestTo("https://api.themoviedb.org/3/movie/603"))
                .andRespond(withSuccess("""
                        {"id": 603, "release_date": "1999-03-31", "status": "Released"}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbMovieDetails> result = tmdbClient.getMovieDetails("603");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[getTvDetails] Should Parse NextEpisodeToAir - When Series Has One Scheduled")
    void shouldParseNextEpisodeToAirWhenSeriesHasOneScheduled() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/tv/1396"))
                .andRespond(withSuccess("""
                        {"id": 1396, "status": "Returning Series",
                         "next_episode_to_air": {"air_date": "2026-09-01", "season_number": 6, "episode_number": 1}}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbTvDetails> result = tmdbClient.getTvDetails("1396");

        assertThat(result).isPresent();
        assertThat(result.get().nextEpisodeToAir().airDate()).isEqualTo("2026-09-01");
        assertThat(result.get().nextEpisodeToAir().seasonNumber()).isEqualTo(6);
    }

    @Test
    @DisplayName("[getPersonCombinedCredits] Should Parse Cast And Crew Credit Ids - When TMDB Responds")
    void shouldParseCastAndCrewCreditIdsWhenTmdbResponds() {
        mockServer.expect(requestTo("https://api.themoviedb.org/3/person/6193/combined_credits"))
                .andRespond(withSuccess("""
                        {"cast": [{"id": 603, "media_type": "movie"}], "crew": [{"id": 1396, "media_type": "tv"}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<TmdbPersonCredits> result = tmdbClient.getPersonCombinedCredits("6193");

        assertThat(result).isPresent();
        assertThat(result.get().cast()).extracting(TmdbCredit::id).containsExactly("603");
        assertThat(result.get().crew()).extracting(TmdbCredit::id).containsExactly("1396");
    }
}
```

- [ ] **Step 6: Run the test to verify it fails (classes don't exist yet)**

Run: `mvnw.cmd test "-Dtest=TmdbClientTest"`
Expected: FAIL — compilation error, `TmdbClient`/DTOs not found.

- [ ] **Step 7: Create the DTOs/config/client from steps 1-4, then re-run**

Run: `mvnw.cmd test "-Dtest=TmdbClientTest"`
Expected: PASS (5 tests).

*Note:* `getPersonCombinedCredits` is declared above returning `Optional<TmdbPersonCredits>` (not `TmdbPersonCredits` as in the interface summary) since it goes through the same `callWithRetry` — a caller must handle "TMDB unreachable" the same way for every method. Task 8 relies on `Optional<TmdbPersonCredits>`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/common/tmdb src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/test/java/com/watchwise/watchwise_api/common/tmdb
git commit -m "feat(tmdb): add TmdbClient for movie/tv/person lookups"
```

**Blocked without a real TMDB API key:** this task's tests all run offline via `MockRestServiceServer` and need no key. Only running `spring-boot:run` against the real TMDB API needs `app.tmdb.api-key` populated (via `APP_TMDB_API_KEY` env var, not committed).

---

## Task 2: `TrackedContentState` entity, migration, repository

**Files:**
- Create: `src/main/resources/db/migration/V38__create-tracked-content-states-table.sql`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/entity/TrackedContentState.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/repository/TrackedContentStateRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/repository/TrackedContentStateRepositoryTest.java`

**Interfaces:**
- Consumes: `Content` (existing entity, `content.entity.Content`).
- Produces: `TrackedContentState` fields — `id`, `content` (FK), `lastKnownReleaseDate: LocalDate`, `lastKnownStatus: String`, `nextEpisodeAirDate: LocalDate`, `nextEpisodeSeasonNumber: Integer`, `nextEpisodeNumber: Integer`, `lastCheckedAt: LocalDateTime`. `TrackedContentStateRepository.findByContentId(UUID contentId): Optional<TrackedContentState>`.

- [ ] **Step 1: Write the migration**

```sql
-- V38__create-tracked-content-states-table.sql
CREATE TABLE tracked_content_states (
    id UUID PRIMARY KEY,
    content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    last_known_release_date DATE,
    last_known_status VARCHAR(30),
    next_episode_air_date DATE,
    next_episode_season_number INTEGER,
    next_episode_number INTEGER,
    last_checked_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_tracked_content_states_content_id UNIQUE (content_id)
);
```

- [ ] **Step 2: Write the failing repository test**

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TrackedContentStateRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TrackedContentStateRepository trackedContentStateRepository;

    @Autowired
    private ContentRepository contentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Content movie;

    @BeforeEach
    void setUp() {
        trackedContentStateRepository.deleteAll();
        contentRepository.deleteAll();

        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("[findByContentId] Should Return The Row - When It Exists")
    void shouldReturnTheRowWhenItExists() {
        trackedContentStateRepository.saveAndFlush(buildState(movie, "Released"));
        entityManager.clear();

        Optional<TrackedContentState> result = trackedContentStateRepository.findByContentId(movie.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getLastKnownStatus()).isEqualTo("Released");
    }

    @Test
    @DisplayName("[findByContentId] Should Return Empty - When Content Is Not Tracked Yet")
    void shouldReturnEmptyWhenContentIsNotTrackedYet() {
        Optional<TrackedContentState> result = trackedContentStateRepository.findByContentId(movie.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete The Row - When The Content Is Deleted")
    void shouldCascadeDeleteTheRowWhenTheContentIsDeleted() {
        trackedContentStateRepository.saveAndFlush(buildState(movie, "Released"));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(movie.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(trackedContentStateRepository.findByContentId(movie.getId())).isEmpty();
    }

    private TrackedContentState buildState(Content content, String status) {
        return TrackedContentState.builder()
                .content(content)
                .lastKnownReleaseDate(LocalDate.of(1999, 3, 31))
                .lastKnownStatus(status)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=TrackedContentStateRepositoryTest"`
Expected: FAIL — `TrackedContentState`/`TrackedContentStateRepository` don't exist.

- [ ] **Step 4: Write the entity**

```java
package com.watchwise.watchwise_api.notification.entity;

import com.watchwise.watchwise_api.content.entity.Content;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tracked_content_states")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrackedContentState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "last_known_release_date")
    @Setter
    private LocalDate lastKnownReleaseDate;

    @Column(name = "last_known_status", length = 30)
    @Setter
    private String lastKnownStatus;

    @Column(name = "next_episode_air_date")
    @Setter
    private LocalDate nextEpisodeAirDate;

    @Column(name = "next_episode_season_number")
    @Setter
    private Integer nextEpisodeSeasonNumber;

    @Column(name = "next_episode_number")
    @Setter
    private Integer nextEpisodeNumber;

    @Column(name = "last_checked_at", nullable = false)
    @Setter
    private LocalDateTime lastCheckedAt;
}
```

- [ ] **Step 5: Write the repository**

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrackedContentStateRepository extends JpaRepository<TrackedContentState, UUID> {

    Optional<TrackedContentState> findByContentId(UUID contentId);

}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=TrackedContentStateRepositoryTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V38__create-tracked-content-states-table.sql src/main/java/com/watchwise/watchwise_api/notification/entity/TrackedContentState.java src/main/java/com/watchwise/watchwise_api/notification/repository/TrackedContentStateRepository.java src/test/java/com/watchwise/watchwise_api/notification/repository/TrackedContentStateRepositoryTest.java
git commit -m "feat(notification): add TrackedContentState cache table"
```

---

## Task 3: `TrackedPersonState` + `TrackedPersonCredit` entities, migration, repositories

**Files:**
- Create: `src/main/resources/db/migration/V39__create-tracked-person-tables.sql`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/entity/TrackedPersonState.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/entity/TrackedPersonCredit.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/repository/TrackedPersonStateRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/repository/TrackedPersonCreditRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/repository/TrackedPersonStateRepositoryTest.java`

**Interfaces:**
- Produces: `TrackedPersonState(id, personTmdbId, lastCheckedAt)`; `TrackedPersonCredit(id, trackedPersonState, creditTmdbId, creditType: ContentType)`; `TrackedPersonStateRepository.findByPersonTmdbId(String): Optional<TrackedPersonState>`; `TrackedPersonCreditRepository.findByTrackedPersonStateId(UUID): List<TrackedPersonCredit>`; `TrackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(UUID, String): boolean`.

- [ ] **Step 1: Write the migration**

```sql
-- V39__create-tracked-person-tables.sql
CREATE TABLE tracked_person_states (
    id UUID PRIMARY KEY,
    person_tmdb_id VARCHAR(20) NOT NULL,
    last_checked_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_tracked_person_states_person_tmdb_id UNIQUE (person_tmdb_id)
);

CREATE TABLE tracked_person_credits (
    id UUID PRIMARY KEY,
    tracked_person_state_id UUID NOT NULL REFERENCES tracked_person_states(id) ON DELETE CASCADE,
    credit_tmdb_id VARCHAR(20) NOT NULL,
    credit_type VARCHAR(6) NOT NULL,
    CONSTRAINT uq_tracked_person_credits_state_credit UNIQUE (tracked_person_state_id, credit_tmdb_id)
);

CREATE INDEX idx_tracked_person_credits_state_id ON tracked_person_credits(tracked_person_state_id);
```

- [ ] **Step 2: Write the failing repository test**

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TrackedPersonStateRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TrackedPersonStateRepository trackedPersonStateRepository;

    @Autowired
    private TrackedPersonCreditRepository trackedPersonCreditRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        trackedPersonCreditRepository.deleteAll();
        trackedPersonStateRepository.deleteAll();
    }

    @Test
    @DisplayName("[findByPersonTmdbId] Should Return The Row - When It Exists")
    void shouldReturnTheRowWhenItExists() {
        trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        entityManager.clear();

        Optional<TrackedPersonState> result = trackedPersonStateRepository.findByPersonTmdbId("6193");

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("[save] Should Throw DataIntegrityViolationException - When PersonTmdbId Already Tracked")
    void shouldThrowDataIntegrityViolationExceptionWhenPersonTmdbIdAlreadyTracked() {
        trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        entityManager.clear();

        assertThatThrownBy(() -> trackedPersonStateRepository.saveAndFlush(buildState("6193")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[findByTrackedPersonStateId] Should Return Only That Person's Credits - When Multiple People Are Tracked")
    void shouldReturnOnlyThatPersonsCreditsWhenMultiplePeopleAreTracked() {
        TrackedPersonState brad = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        TrackedPersonState kate = trackedPersonStateRepository.saveAndFlush(buildState("1813"));
        trackedPersonCreditRepository.saveAndFlush(buildCredit(brad, "603"));
        trackedPersonCreditRepository.saveAndFlush(buildCredit(kate, "597"));
        entityManager.clear();

        List<TrackedPersonCredit> result = trackedPersonCreditRepository.findByTrackedPersonStateId(brad.getId());

        assertThat(result).extracting(TrackedPersonCredit::getCreditTmdbId).containsExactly("603");
    }

    @Test
    @DisplayName("[existsByTrackedPersonStateIdAndCreditTmdbId] Should Return False - When Credit Is New")
    void shouldReturnFalseWhenCreditIsNew() {
        TrackedPersonState brad = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        entityManager.clear();

        boolean result = trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(brad.getId(), "603");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete Credits - When The TrackedPersonState Is Deleted")
    void shouldCascadeDeleteCreditsWhenTheTrackedPersonStateIsDeleted() {
        TrackedPersonState brad = trackedPersonStateRepository.saveAndFlush(buildState("6193"));
        TrackedPersonCredit credit = trackedPersonCreditRepository.saveAndFlush(buildCredit(brad, "603"));
        entityManager.clear();

        trackedPersonStateRepository.delete(trackedPersonStateRepository.findById(brad.getId()).orElseThrow());
        trackedPersonStateRepository.flush();

        assertThat(trackedPersonCreditRepository.findById(credit.getId())).isEmpty();
    }

    private TrackedPersonState buildState(String personTmdbId) {
        return TrackedPersonState.builder()
                .personTmdbId(personTmdbId)
                .lastCheckedAt(LocalDateTime.now())
                .build();
    }

    private TrackedPersonCredit buildCredit(TrackedPersonState state, String creditTmdbId) {
        return TrackedPersonCredit.builder()
                .trackedPersonState(state)
                .creditTmdbId(creditTmdbId)
                .creditType(ContentType.MOVIE)
                .build();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=TrackedPersonStateRepositoryTest"`
Expected: FAIL — classes don't exist.

- [ ] **Step 4: Write the entities**

```java
package com.watchwise.watchwise_api.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tracked_person_states")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrackedPersonState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @Column(name = "person_tmdb_id", length = 20, nullable = false)
    @Setter
    private String personTmdbId;

    @Column(name = "last_checked_at", nullable = false)
    @Setter
    private LocalDateTime lastCheckedAt;
}
```

```java
package com.watchwise.watchwise_api.notification.entity;

import com.watchwise.watchwise_api.content.entity.ContentType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "tracked_person_credits")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class TrackedPersonCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracked_person_state_id", nullable = false)
    private TrackedPersonState trackedPersonState;

    @Column(name = "credit_tmdb_id", length = 20, nullable = false)
    @Setter
    private String creditTmdbId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_type", length = 6, nullable = false)
    @Setter
    private ContentType creditType;
}
```

- [ ] **Step 5: Write the repositories**

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrackedPersonStateRepository extends JpaRepository<TrackedPersonState, UUID> {

    Optional<TrackedPersonState> findByPersonTmdbId(String personTmdbId);

}
```

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrackedPersonCreditRepository extends JpaRepository<TrackedPersonCredit, UUID> {

    List<TrackedPersonCredit> findByTrackedPersonStateId(UUID trackedPersonStateId);

    boolean existsByTrackedPersonStateIdAndCreditTmdbId(UUID trackedPersonStateId, String creditTmdbId);

}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=TrackedPersonStateRepositoryTest"`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V39__create-tracked-person-tables.sql src/main/java/com/watchwise/watchwise_api/notification/entity/TrackedPersonState.java src/main/java/com/watchwise/watchwise_api/notification/entity/TrackedPersonCredit.java src/main/java/com/watchwise/watchwise_api/notification/repository/TrackedPersonStateRepository.java src/main/java/com/watchwise/watchwise_api/notification/repository/TrackedPersonCreditRepository.java src/test/java/com/watchwise/watchwise_api/notification/repository/TrackedPersonStateRepositoryTest.java
git commit -m "feat(notification): add TrackedPersonState/TrackedPersonCredit cache tables"
```

---

## Task 4: `Notification` entity, migration, repository

**Files:**
- Create: `src/main/resources/db/migration/V40__create-notifications-table.sql`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationType.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/entity/Notification.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java`

**Interfaces:**
- Produces: `enum NotificationType { RELEASE, ANNOUNCED_DATE, CANCELLED, RENEWED, NEW_EPISODE, FOLLOWED_PERSON_NEW_CREDIT }`; `Notification(id, user, type, message, content, personTmdbId, isRead, createdAt, updatedAt)`; `NotificationRepository.findByUserIdOrderByCreatedAtDesc(UUID, Pageable): Page<Notification>`, `findByUserIdAndIsReadOrderByCreatedAtDesc(UUID, boolean, Pageable): Page<Notification>`.

- [ ] **Step 1: Write the migration**

```sql
-- V40__create-notifications-table.sql
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    message VARCHAR(280) NOT NULL,
    content_id UUID NOT NULL REFERENCES contents(id) ON DELETE CASCADE,
    person_tmdb_id VARCHAR(20),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_notifications_user_id_created_at ON notifications(user_id, created_at DESC);
```

- [ ] **Step 2: Write the failing repository test**

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class NotificationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private Content movie;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.saveAndFlush(User.builder()
                .username("lucas").email("lucas@email.com").password("hashed")
                .profilePicture("https://example.com/p.png").isProfilePublic(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    @Test
    @DisplayName("[findByUserIdOrderByCreatedAtDesc] Should Return Notifications Newest First - When Multiple Exist")
    void shouldReturnNotificationsNewestFirstWhenMultipleExist() {
        Notification older = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now().minusDays(1)));
        Notification newer = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        entityManager.clear();

        Page<Notification> result = notificationRepository.findByUserIdOrderByCreatedAtDesc(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Notification::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndIsReadOrderByCreatedAtDesc] Should Return Only Unread - When isRead Filter Is False")
    void shouldReturnOnlyUnreadWhenIsReadFilterIsFalse() {
        Notification unread = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        Notification read = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        read.setIsRead(true);
        notificationRepository.saveAndFlush(read);
        entityManager.clear();

        Page<Notification> result = notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(lucas.getId(), false, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Notification::getId).containsExactly(unread.getId());
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete - When The User Is Deleted")
    void shouldCascadeDeleteWhenTheUserIsDeleted() {
        Notification notification = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(notificationRepository.findById(notification.getId())).isEmpty();
    }

    private Notification buildNotification(LocalDateTime createdAt) {
        return Notification.builder()
                .user(lucas)
                .type(NotificationType.RELEASE)
                .message("The Matrix is out now")
                .content(movie)
                .isRead(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=NotificationRepositoryTest"`
Expected: FAIL — classes don't exist.

- [ ] **Step 4: Write `NotificationType` and `Notification`**

```java
package com.watchwise.watchwise_api.notification.entity;

public enum NotificationType {
    RELEASE,
    ANNOUNCED_DATE,
    CANCELLED,
    RENEWED,
    NEW_EPISODE,
    FOLLOWED_PERSON_NEW_CREDIT
}
```

```java
package com.watchwise.watchwise_api.notification.entity;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    @Setter
    private NotificationType type;

    @Column(length = 280, nullable = false)
    @Setter
    private String message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "person_tmdb_id", length = 20)
    @Setter
    private String personTmdbId;

    @Column(name = "is_read", nullable = false)
    @Setter
    private Boolean isRead;

    @Column(name = "created_at", nullable = false)
    @Setter
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 5: Write the repository**

```java
package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndIsReadOrderByCreatedAtDesc(UUID userId, boolean isRead, Pageable pageable);

}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=NotificationRepositoryTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V40__create-notifications-table.sql src/main/java/com/watchwise/watchwise_api/notification/entity/NotificationType.java src/main/java/com/watchwise/watchwise_api/notification/entity/Notification.java src/main/java/com/watchwise/watchwise_api/notification/repository/NotificationRepository.java src/test/java/com/watchwise/watchwise_api/notification/repository/NotificationRepositoryTest.java
git commit -m "feat(notification): add Notification entity and table"
```

---

## Task 5: `ContentChangeDetector` (pure diff logic)

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/notification/tracking/ContentChangeEvent.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/tracking/ContentChangeDetector.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/tracking/ContentChangeDetectorTest.java`

**Interfaces:**
- Consumes: `TrackedContentState` (may be `null` for a first-ever check), `TmdbMovieDetails`/`TmdbTvDetails` (from Task 1), `ContentType`.
- Produces: `record ContentChangeEvent(NotificationType type, LocalDate relevantDate, Integer seasonNumber, Integer episodeNumber)`; `ContentChangeDetector.detectMovieChange(TrackedContentState previous, TmdbMovieDetails fresh, LocalDate today): Optional<ContentChangeEvent>`; `ContentChangeDetector.detectTvChange(TrackedContentState previous, TmdbTvDetails fresh, LocalDate today): List<ContentChangeEvent>` (a TV check can yield both a status-based event and a `NEW_EPISODE` event in the same cycle, so callers get every event that applies, not just one).

- [ ] **Step 1: Write `ContentChangeEvent`**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.entity.NotificationType;

import java.time.LocalDate;

public record ContentChangeEvent(
        NotificationType type,
        LocalDate relevantDate,
        Integer seasonNumber,
        Integer episodeNumber) {
}
```

- [ ] **Step 2: Write the failing tests — one per row of the design's diff table**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbNextEpisode;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContentChangeDetectorTest {

    private final ContentChangeDetector detector = new ContentChangeDetector();
    private final LocalDate today = LocalDate.of(2026, 8, 29);

    @Test
    @DisplayName("[detectMovieChange] Should Return ANNOUNCED_DATE - When A Future Release Date First Appears")
    void shouldReturnAnnouncedDateWhenAFutureReleaseDateFirstAppears() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(null).lastKnownStatus("Planned").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-12-01", "Planned");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NotificationType.ANNOUNCED_DATE);
        assertThat(result.get().relevantDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return RELEASE - When Known Future Date Has Now Passed")
    void shouldReturnReleaseWhenKnownFutureDateHasNowPassed() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(LocalDate.of(2026, 8, 20)).lastKnownStatus("Post Production").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-08-20", "Released");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NotificationType.RELEASE);
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return CANCELLED - When Status Changes To Canceled")
    void shouldReturnCancelledWhenStatusChangesToCanceled() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(null).lastKnownStatus("In Production").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "", "Canceled");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo(NotificationType.CANCELLED);
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return Empty - When Nothing Changed")
    void shouldReturnEmptyWhenNothingChanged() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownReleaseDate(LocalDate.of(2026, 12, 1)).lastKnownStatus("Planned").build();
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "2026-12-01", "Planned");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectMovieChange] Should Return Empty - When First Check Has No Release Date Yet")
    void shouldReturnEmptyWhenFirstCheckHasNoReleaseDateYet() {
        TmdbMovieDetails fresh = new TmdbMovieDetails("603", "", "Planned");

        Optional<ContentChangeEvent> result = detector.detectMovieChange(null, fresh, today);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[detectTvChange] Should Return RENEWED - When Status Moves From Ended To Returning Series")
    void shouldReturnRenewedWhenStatusMovesFromEndedToReturningSeries() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownStatus("Ended").build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series", null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.RENEWED);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return CANCELLED - When Status Changes To Canceled")
    void shouldReturnCancelledWhenStatusChangesToCanceled() {
        TrackedContentState previous = TrackedContentState.builder().lastKnownStatus("Returning Series").build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Canceled", null);

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.CANCELLED);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return NEW_EPISODE - When The Known Next Episode Air Date Has Passed")
    void shouldReturnNewEpisodeWhenTheKnownNextEpisodeAirDateHasPassed() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .nextEpisodeAirDate(LocalDate.of(2026, 8, 25))
                .nextEpisodeSeasonNumber(6).nextEpisodeNumber(3)
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series",
                new TmdbNextEpisode("2026-09-05", 6, 4));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type).containsExactly(NotificationType.NEW_EPISODE);
        assertThat(result.getFirst().seasonNumber()).isEqualTo(6);
        assertThat(result.getFirst().episodeNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Both RENEWED And NEW_EPISODE - When Both Conditions Are True In The Same Cycle")
    void shouldReturnBothRenewedAndNewEpisodeWhenBothConditionsAreTrueInTheSameCycle() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Ended")
                .nextEpisodeAirDate(LocalDate.of(2026, 8, 25))
                .nextEpisodeSeasonNumber(6).nextEpisodeNumber(3)
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series",
                new TmdbNextEpisode("2026-09-05", 6, 4));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).extracting(ContentChangeEvent::type)
                .containsExactlyInAnyOrder(NotificationType.RENEWED, NotificationType.NEW_EPISODE);
    }

    @Test
    @DisplayName("[detectTvChange] Should Return Empty List - When Nothing Changed And No Episode Air Date Passed")
    void shouldReturnEmptyListWhenNothingChangedAndNoEpisodeAirDatePassed() {
        TrackedContentState previous = TrackedContentState.builder()
                .lastKnownStatus("Returning Series")
                .nextEpisodeAirDate(LocalDate.of(2026, 9, 5))
                .nextEpisodeSeasonNumber(6).nextEpisodeNumber(4)
                .build();
        TmdbTvDetails fresh = new TmdbTvDetails("1396", "Returning Series",
                new TmdbNextEpisode("2026-09-05", 6, 4));

        List<ContentChangeEvent> result = detector.detectTvChange(previous, fresh, today);

        assertThat(result).isEmpty();
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=ContentChangeDetectorTest"`
Expected: FAIL — `ContentChangeDetector` doesn't exist.

- [ ] **Step 4: Write `ContentChangeDetector`**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ContentChangeDetector {

    private static final String CANCELED_STATUS = "Canceled";

    public Optional<ContentChangeEvent> detectMovieChange(TrackedContentState previous, TmdbMovieDetails fresh, LocalDate today) {
        LocalDate freshReleaseDate = parseDate(fresh.releaseDate());
        LocalDate previousReleaseDate = previous == null ? null : previous.getLastKnownReleaseDate();
        String previousStatus = previous == null ? null : previous.getLastKnownStatus();

        if (!CANCELED_STATUS.equals(previousStatus) && CANCELED_STATUS.equals(fresh.status())) {
            return Optional.of(new ContentChangeEvent(NotificationType.CANCELLED, null, null, null));
        }

        if (previousReleaseDate != null && !today.isBefore(previousReleaseDate)) {
            return Optional.of(new ContentChangeEvent(NotificationType.RELEASE, previousReleaseDate, null, null));
        }

        if (previousReleaseDate == null && freshReleaseDate != null && freshReleaseDate.isAfter(today)) {
            return Optional.of(new ContentChangeEvent(NotificationType.ANNOUNCED_DATE, freshReleaseDate, null, null));
        }

        return Optional.empty();
    }

    public List<ContentChangeEvent> detectTvChange(TrackedContentState previous, TmdbTvDetails fresh, LocalDate today) {
        List<ContentChangeEvent> events = new ArrayList<>();
        String previousStatus = previous == null ? null : previous.getLastKnownStatus();

        if (!CANCELED_STATUS.equals(previousStatus) && CANCELED_STATUS.equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.CANCELLED, null, null, null));
        } else if (isEndedOrCancelled(previousStatus) && "Returning Series".equals(fresh.status())) {
            events.add(new ContentChangeEvent(NotificationType.RENEWED, null, null, null));
        }

        if (previous != null && previous.getNextEpisodeAirDate() != null
                && !today.isBefore(previous.getNextEpisodeAirDate())) {
            events.add(new ContentChangeEvent(NotificationType.NEW_EPISODE, previous.getNextEpisodeAirDate(),
                    previous.getNextEpisodeSeasonNumber(), previous.getNextEpisodeNumber()));
        }

        return events;
    }

    private boolean isEndedOrCancelled(String status) {
        return "Ended".equals(status) || CANCELED_STATUS.equals(status);
    }

    private LocalDate parseDate(String value) {
        return StringUtils.hasText(value) ? LocalDate.parse(value) : null;
    }
}
```

*Note:* `RELEASE` only fires when a **previously known** release date has now arrived (`previousReleaseDate != null && !today.isBefore(previousReleaseDate)`) — a title with no previously known date takes the `ANNOUNCED_DATE` branch instead, never `RELEASE`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=ContentChangeDetectorTest"`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/notification/tracking src/test/java/com/watchwise/watchwise_api/notification/tracking
git commit -m "feat(notification): add ContentChangeDetector diff logic"
```

---

## Task 6: `Notification` service, mapper, DTOs, controller

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/notification/dto/NotificationResponseDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/mapper/NotificationMapper.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/service/NotificationService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/service/impl/NotificationServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/notification/controller/NotificationController.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/service/impl/NotificationServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/controller/NotificationControllerIntegrationTest.java`

**Interfaces:**
- Consumes: `Notification`, `NotificationRepository` (Task 4), `ContentRefDTO`/`ContentMapper` (existing), `PageRequestFactory`, `PageResponseDTO`.
- Produces: `record NotificationResponseDTO(UUID id, NotificationType type, String message, ContentRefDTO content, String personTmdbId, boolean isRead, LocalDateTime createdAt, LocalDateTime updatedAt)`; `NotificationService.getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize): Page<NotificationResponseDTO>`; `NotificationService.markAsRead(UUID userId, UUID notificationId): void`.

- [ ] **Step 1: Write `NotificationResponseDTO`**

```java
package com.watchwise.watchwise_api.notification.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.notification.entity.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponseDTO(
        UUID id,
        NotificationType type,
        String message,
        ContentRefDTO content,
        String personTmdbId,
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
```

- [ ] **Step 2: Write `NotificationMapper`**

```java
package com.watchwise.watchwise_api.notification.mapper;

import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", uses = ContentMapper.class, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface NotificationMapper {

    NotificationResponseDTO notificationToNotificationResponseDto(Notification notification);

}
```

- [ ] **Step 3: Write the failing service test**

```java
package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @Spy
    private NotificationMapper notificationMapper = new NotificationMapperImplStub();

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private UUID userId;
    private Notification notification;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("lucas").email("lucas@email.com").password("hashed")
                .isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Content content = Content.builder().id(UUID.randomUUID()).tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        notification = Notification.builder()
                .id(UUID.randomUUID()).user(user).type(NotificationType.RELEASE)
                .message("The Matrix is out now").content(content).isRead(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("[getNotifications] Should Return Mapped Page - When Notifications Exist")
    void shouldReturnMappedPageWhenNotificationsExist() {
        Page<Notification> page = new PageImpl<>(List.of(notification));
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class))).thenReturn(page);

        Page<NotificationResponseDTO> result = notificationService.getNotifications(userId, null, 1, 10);

        assertThat(result.getContent()).extracting(NotificationResponseDTO::id).containsExactly(notification.getId());
    }

    @Test
    @DisplayName("[getNotifications] Should Filter By isRead - When isRead Is Provided")
    void shouldFilterByIsReadWhenIsReadIsProvided() {
        when(notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(eq(userId), eq(false), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(notification)));

        notificationService.getNotifications(userId, false, 1, 10);

        verify(notificationRepository).findByUserIdAndIsReadOrderByCreatedAtDesc(eq(userId), eq(false), any(PageRequest.class));
        verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getNotifications] Should Return Empty Page - When User Has No Notifications")
    void shouldReturnEmptyPageWhenUserHasNoNotifications() {
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(PageRequest.class))).thenReturn(Page.empty());

        Page<NotificationResponseDTO> result = notificationService.getNotifications(userId, null, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[markAsRead] Should Set isRead True And Save - When Notification Belongs To The User")
    void shouldSetIsReadTrueAndSaveWhenNotificationBelongsToTheUser() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        notificationService.markAsRead(userId, notification.getId());

        assertThat(notification.getIsRead()).isTrue();
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("[markAsRead] Should Throw NotFoundException - When Notification Does Not Exist")
    void shouldThrowNotFoundExceptionWhenNotificationDoesNotExist() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(userId, notification.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("[markAsRead] Should Throw ForbiddenException - When Notification Belongs To A Different User")
    void shouldThrowForbiddenExceptionWhenNotificationBelongsToADifferentUser() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markAsRead(UUID.randomUUID(), notification.getId()))
                .isInstanceOf(ForbiddenException.class);

        verify(notificationRepository, never()).save(any());
    }
}
```

`NotificationMapperImplStub` is a tiny hand-written stub (MapStruct's generated `NotificationMapperImpl` needs an annotation-processing build pass that a plain unit test doesn't trigger before this class exists) — write it next to the test:

```java
package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;

class NotificationMapperImplStub implements NotificationMapper {
    @Override
    public NotificationResponseDTO notificationToNotificationResponseDto(Notification notification) {
        ContentRefDTO contentRef = new ContentRefDTO(
                notification.getContent().getId(), notification.getContent().getTmdbId(),
                notification.getContent().getType(), notification.getContent().getSeriesTmdbId(),
                notification.getContent().getSeasonNumber(), notification.getContent().getEpisodeNumber(),
                notification.getContent().getIsSeasonFinale(), notification.getContent().getIsSeriesFinale(),
                notification.getContent().getRuntimeMinutes(), notification.getContent().getGenres(),
                notification.getContent().getReleaseYear(), notification.getContent().getCountries());
        return new NotificationResponseDTO(
                notification.getId(), notification.getType(), notification.getMessage(), contentRef,
                notification.getPersonTmdbId(), notification.getIsRead(),
                notification.getCreatedAt(), notification.getUpdatedAt());
    }
}
```

*Before writing this stub, read `src/main/java/com/watchwise/watchwise_api/content/dto/ContentRefDTO.java` to confirm its exact field order/names — copy them verbatim rather than trusting the guess above.*

- [ ] **Step 4: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=NotificationServiceImplTest"`
Expected: FAIL — `NotificationService`/`NotificationServiceImpl` don't exist.

- [ ] **Step 5: Write `NotificationService` + `NotificationServiceImpl`**

```java
package com.watchwise.watchwise_api.notification.service;

import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface NotificationService {

    Page<NotificationResponseDTO> getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize);

    void markAsRead(UUID userId, UUID notificationId);

}
```

```java
package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public Page<NotificationResponseDTO> getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize) {
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<Notification> page = isRead == null
                ? notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest)
                : notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(userId, isRead, pageRequest);

        return page.map(notificationMapper::notificationToNotificationResponseDto);
    }

    @Override
    public void markAsRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));

        if (!notification.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This notification does not belong to you");
        }

        notification.setIsRead(true);
        notification.setUpdatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=NotificationServiceImplTest"`
Expected: PASS (5 tests). Add the remaining `PageRequestFactory` boundary cases from `CLAUDE.md`'s baseline checklist (page number null/zero/positive/negative, page size null/valid/at-max/exceeds/negative/zero) using the exact pattern already written in `FollowedPersonServiceImplTest` (lines 312-420 of that file) — same assertions, swapped to `notificationRepository.findByUserIdOrderByCreatedAtDesc` and `pageRequestCaptor`.

- [ ] **Step 7: Write `NotificationController` + its unit test**

```java
package com.watchwise.watchwise_api.notification.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PageResponseDTO<NotificationResponseDTO>> getNotifications(
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        Page<NotificationResponseDTO> notifications = notificationService.getNotifications(getCurrentUserId(), isRead, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(notifications));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID notificationId) {
        notificationService.markAsRead(getCurrentUserId(), notificationId);
        return ResponseEntity.noContent().build();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

```java
package com.watchwise.watchwise_api.notification.controller;

import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationController notificationController;

    private UUID userId;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getNotifications] Should Return 200 With Mapped Page - When Called")
    void shouldReturn200WithMappedPageWhenCalled() {
        when(notificationService.getNotifications(eq(userId), eq(null), eq(1), eq(10)))
                .thenReturn(new PageImpl<>(List.<NotificationResponseDTO>of()));

        ResponseEntity<?> response = notificationController.getNotifications(null, 1, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("[getNotifications] Should Resolve Current User From SecurityContext - When Called")
    void shouldResolveCurrentUserFromSecurityContextWhenCalled() {
        when(notificationService.getNotifications(any(), any(), any(), any())).thenReturn(Page.empty());

        notificationController.getNotifications(true, 1, 10);

        verify(notificationService).getNotifications(userId, true, 1, 10);
    }

    @Test
    @DisplayName("[markAsRead] Should Return 204 And Delegate To Service - When Called")
    void shouldReturn204AndDelegateToServiceWhenCalled() {
        UUID notificationId = UUID.randomUUID();

        ResponseEntity<Void> response = notificationController.markAsRead(notificationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(notificationService).markAsRead(userId, notificationId);
    }
}
```

- [ ] **Step 8: Run all notification tests**

Run: `mvnw.cmd test "-Dtest=NotificationServiceImplTest,NotificationControllerTest"`
Expected: PASS.

- [ ] **Step 9: Write the integration test**

This follows `FollowedPersonControllerIntegrationTest`'s exact structure: the same `RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken)` record, the same `registerUser(username)` helper (calls `POST /auth/register`, pulls the `access_token`/`XSRF-TOKEN` cookies off the response), the same `RequestThrottlerTestSupport.reset(requestThrottler)` call in `@BeforeEach` (not a `requestThrottler.reset()` instance method — that method doesn't exist), the same `AutoConfigureMockMvc` import (`org.springframework.boot.webmvc.test.autoconfigure`, not `org.springframework.boot.test.web.servlet`), and no `/api/v1` prefix on request paths (`@AutoConfigureMockMvc` in this project's test setup already dispatches without the configured context path).

```java
package com.watchwise.watchwise_api.notification.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    private Content movie;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        notificationRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);

        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123",
                    "isProfilePublic": true
                }
                """.formatted(username, username);

        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessTokenCookie, csrfCookie);
    }

    private Notification buildNotification(UUID ownerId) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        return Notification.builder()
                .user(owner).type(NotificationType.RELEASE).message("The Matrix is out now")
                .content(movie).isRead(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("[getNotifications] Should Return Only The Caller's Notifications - When Authenticated")
    void shouldReturnOnlyTheCallersNotificationsWhenAuthenticated() throws Exception {
        RegisteredUser lucas = registerUser("notifok");
        RegisteredUser marina = registerUser("notifother");
        notificationRepository.saveAndFlush(buildNotification(lucas.id()));
        notificationRepository.saveAndFlush(buildNotification(marina.id()));

        mockMvc.perform(get("/notifications").cookie(lucas.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("[getNotifications] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(get("/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getNotifications] Should Return BadRequest - When Page Is Negative")
    void shouldReturnBadRequestWhenPageIsNegative() throws Exception {
        RegisteredUser lucas = registerUser("notifbadpage");

        mockMvc.perform(get("/notifications").param("page", "-1").cookie(lucas.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[markAsRead] Should Return NoContent And Persist isRead True - When Notification Belongs To The Caller")
    void shouldReturnNoContentAndPersistIsReadTrueWhenNotificationBelongsToTheCaller() throws Exception {
        RegisteredUser lucas = registerUser("notifreadok");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(lucas.accessToken(), lucas.csrfToken())
                        .header("X-XSRF-TOKEN", lucas.csrfToken().getValue()))
                .andExpect(status().isNoContent());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getIsRead()).isTrue();
    }

    @Test
    @DisplayName("[markAsRead] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForMarkAsRead() throws Exception {
        RegisteredUser lucas = registerUser("notifreadnoauth");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[markAsRead] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissing() throws Exception {
        RegisteredUser lucas = registerUser("notifreadnocsrf");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(lucas.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[markAsRead] Should Return NotFound - When Notification Does Not Exist")
    void shouldReturnNotFoundWhenNotificationDoesNotExist() throws Exception {
        RegisteredUser lucas = registerUser("notifreadnotfound");

        mockMvc.perform(patch("/notifications/{id}/read", UUID.randomUUID())
                        .cookie(lucas.accessToken(), lucas.csrfToken())
                        .header("X-XSRF-TOKEN", lucas.csrfToken().getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[markAsRead] Should Return Forbidden - When Notification Belongs To A Different User")
    void shouldReturnForbiddenWhenNotificationBelongsToADifferentUser() throws Exception {
        RegisteredUser lucas = registerUser("notifreadowner");
        RegisteredUser marina = registerUser("notifreadintruder");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(marina.accessToken(), marina.csrfToken())
                        .header("X-XSRF-TOKEN", marina.csrfToken().getValue()))
                .andExpect(status().isForbidden());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getIsRead()).isFalse();
    }
}
```

- [ ] **Step 10: Run the full test suite for this task**

Run: `mvnw.cmd test "-Dtest=Notification*"`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/notification/dto src/main/java/com/watchwise/watchwise_api/notification/mapper src/main/java/com/watchwise/watchwise_api/notification/service src/main/java/com/watchwise/watchwise_api/notification/controller src/test/java/com/watchwise/watchwise_api/notification/service src/test/java/com/watchwise/watchwise_api/notification/controller
git commit -m "feat(notification): add GET /notifications and PATCH /notifications/{id}/read"
```

---

## Task 7: `ContentTrackingJob` (daily orchestration + fan-out)

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/notification/tracking/ContentTrackingJob.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/watchlist/repository/WatchlistEntryRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/tracking/ContentTrackingJobTest.java`

**Interfaces:**
- Consumes: `TmdbClient` (Task 1), `ContentChangeDetector` (Task 5), `TrackedContentStateRepository` (Task 2), `NotificationRepository` (Task 4), `NewTransactionExecutor`.
- Produces: `ContentTrackingJob.run(): void`; new repository queries `WatchlistEntryRepository.findDistinctTrackedMovieAndSeriesContent(): List<Content>`, `DiaryEntryRepository.findDistinctInProgressSeriesContent(): List<Content>`; `WatchlistEntryRepository.findUserIdsByContentId(UUID): List<UUID>`, `DiaryEntryRepository.findUserIdsWatchingSeries(String seriesTmdbId): List<UUID>`.

- [ ] **Step 1: Add the distinct-tracked-content queries to `WatchlistEntryRepository`**

```java
    @Query("SELECT DISTINCT w.content FROM WatchlistEntry w")
    List<Content> findDistinctTrackedContent();

    @Query("SELECT DISTINCT w.user.id FROM WatchlistEntry w WHERE w.content.id = :contentId")
    List<UUID> findUserIdsByContentId(@Param("contentId") UUID contentId);
```

Add `import com.watchwise.watchwise_api.content.entity.Content;` if not already present (it already is, for `ContentType`'s package — confirm `Content` itself is imported too).

- [ ] **Step 2: Add the in-progress-series query to `DiaryEntryRepository`**

Reuse the existing `findSeriesInProgressByUserId` native query's "no matching SERIES-type entry yet" definition of in-progress, but scoped globally (not per-user) and returning `Content` rows for the `SERIES`-type reference (so `ContentTrackingJob` can call `TmdbClient.getTvDetails` on it directly):

```java
    @Query(value = """
            SELECT DISTINCT sc.*
            FROM contents c
            JOIN diary_entries d ON d.content_id = c.id
            JOIN contents sc ON sc.tmdb_id = c.series_tmdb_id AND sc.type = 'SERIES'
            WHERE c.type = 'EPISODE'
            AND NOT EXISTS (
                SELECT 1 FROM diary_entries d2
                JOIN contents c2 ON c2.id = d2.content_id
                WHERE d2.user_id = d.user_id
                AND c2.type = 'SERIES'
                AND c2.tmdb_id = c.series_tmdb_id
            )
            """, nativeQuery = true)
    List<Content> findDistinctInProgressSeriesContent();

    @Query("""
            SELECT DISTINCT d.user.id FROM DiaryEntry d
            WHERE d.content.type = com.watchwise.watchwise_api.content.entity.ContentType.EPISODE
            AND d.content.seriesTmdbId = :seriesTmdbId
            """)
    List<UUID> findUserIdsWatchingSeries(@Param("seriesTmdbId") String seriesTmdbId);
```

*Note:* `findDistinctInProgressSeriesContent`'s "in progress" definition must stay identical to `findSeriesInProgressByUserId`'s (same NOT EXISTS shape) — if that method's semantics change later, update both together.

- [ ] **Step 3: Add the job's cron property**

Append to `application-dev.properties` and `application-prod.properties`:

```properties
app.content-tracking.cron=0 0 4 * * *
```

- [ ] **Step 4: Write the failing `ContentTrackingJobTest`**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedContentStateRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentTrackingJobTest {

    @Mock private WatchlistEntryRepository watchlistEntryRepository;
    @Mock private DiaryEntryRepository diaryEntryRepository;
    @Mock private TrackedContentStateRepository trackedContentStateRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private TmdbClient tmdbClient;
    @Mock private ContentChangeDetector contentChangeDetector;
    @Mock private NewTransactionExecutor newTransactionExecutor;

    @InjectMocks
    private ContentTrackingJob contentTrackingJob;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    private Content movie;
    private UUID watchingUserId;

    @BeforeEach
    void setUp() {
        movie = Content.builder().id(UUID.randomUUID()).tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        watchingUserId = UUID.randomUUID();

        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("[run] Should Create A Notification For Every Watching User - When A Movie Change Is Detected")
    void shouldCreateANotificationForEveryWatchingUserWhenAMovieChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        TmdbMovieDetails details = new TmdbMovieDetails("603", "2026-08-29", "Released");
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(details));
        when(contentChangeDetector.detectMovieChange(any(), any(), any()))
                .thenReturn(Optional.of(new ContentChangeEvent(NotificationType.RELEASE, null, null, null)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingJob.run();

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getUser().getId()).isEqualTo(watchingUserId);
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.RELEASE);
        assertThat(notificationCaptor.getValue().getContent()).isEqualTo(movie);
    }

    @Test
    @DisplayName("[run] Should Update TrackedContentState - When A Change Is Detected")
    void shouldUpdateTrackedContentStateWhenAChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "2026-08-29", "Released")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any()))
                .thenReturn(Optional.of(new ContentChangeEvent(NotificationType.RELEASE, null, null, null)));
        when(watchlistEntryRepository.findUserIdsByContentId(movie.getId())).thenReturn(List.of(watchingUserId));

        contentTrackingJob.run();

        ArgumentCaptor<TrackedContentState> stateCaptor = ArgumentCaptor.forClass(TrackedContentState.class);
        verify(trackedContentStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastKnownStatus()).isEqualTo("Released");
        assertThat(stateCaptor.getValue().getLastKnownReleaseDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 29));
    }

    @Test
    @DisplayName("[run] Should Not Create A Notification - When No Change Is Detected")
    void shouldNotCreateANotificationWhenNoChangeIsDetected() {
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(trackedContentStateRepository.findByContentId(movie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.of(new TmdbMovieDetails("603", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingJob.run();

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("[run] Should Skip The Item And Continue - When TMDB Returns Nothing For It")
    void shouldSkipTheItemAndContinueWhenTmdbReturnsNothingForIt() {
        Content secondMovie = Content.builder().id(UUID.randomUUID()).tmdbId("999").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(watchlistEntryRepository.findDistinctTrackedContent()).thenReturn(List.of(movie, secondMovie));
        when(diaryEntryRepository.findDistinctInProgressSeriesContent()).thenReturn(List.of());
        when(tmdbClient.getMovieDetails("603")).thenReturn(Optional.empty());
        when(trackedContentStateRepository.findByContentId(secondMovie.getId())).thenReturn(Optional.empty());
        when(tmdbClient.getMovieDetails("999")).thenReturn(Optional.of(new TmdbMovieDetails("999", "", "Planned")));
        when(contentChangeDetector.detectMovieChange(any(), any(), any())).thenReturn(Optional.empty());

        contentTrackingJob.run();

        verify(trackedContentStateRepository, never()).findByContentId(movie.getId());
        verify(tmdbClient).getMovieDetails("999");
    }
}
```

- [ ] **Step 5: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=ContentTrackingJobTest"`
Expected: FAIL — `ContentTrackingJob` doesn't exist.

- [ ] **Step 6: Write `ContentTrackingJob`**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedContentState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedContentStateRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentTrackingJob {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final TrackedContentStateRepository trackedContentStateRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final ContentChangeDetector contentChangeDetector;
    private final NewTransactionExecutor newTransactionExecutor;

    @Scheduled(cron = "${app.content-tracking.cron}")
    public void run() {
        List<Content> tracked = watchlistEntryRepository.findDistinctTrackedContent();
        tracked.forEach(this::processMovieOrSeries);

        List<Content> inProgressSeries = diaryEntryRepository.findDistinctInProgressSeriesContent();
        inProgressSeries.forEach(this::processSeriesForNewEpisode);
    }

    private void processMovieOrSeries(Content content) {
        try {
            if (content.getType() == ContentType.MOVIE) {
                processMovie(content);
            } else if (content.getType() == ContentType.SERIES) {
                processSeries(content, false);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to process tracked content {} ({}): {}", content.getId(), content.getTmdbId(), e.getMessage());
        }
    }

    private void processSeriesForNewEpisode(Content content) {
        try {
            processSeries(content, true);
        } catch (RuntimeException e) {
            log.warn("Failed to process in-progress series {} ({}): {}", content.getId(), content.getTmdbId(), e.getMessage());
        }
    }

    private void processMovie(Content content) {
        Optional<TmdbMovieDetails> fresh = tmdbClient.getMovieDetails(content.getTmdbId());
        if (fresh.isEmpty()) {
            return;
        }

        newTransactionExecutor.runInNewTransaction(() -> {
            TrackedContentState previous = trackedContentStateRepository.findByContentId(content.getId()).orElse(null);
            Optional<ContentChangeEvent> event = contentChangeDetector.detectMovieChange(previous, fresh.get(), LocalDate.now());

            event.ifPresent(e -> notifyWatchers(content, e));
            saveMovieState(content, previous, fresh.get());
            return null;
        });
    }

    private void processSeries(Content content, boolean fromDiary) {
        Optional<TmdbTvDetails> fresh = tmdbClient.getTvDetails(content.getTmdbId());
        if (fresh.isEmpty()) {
            return;
        }

        newTransactionExecutor.runInNewTransaction(() -> {
            TrackedContentState previous = trackedContentStateRepository.findByContentId(content.getId()).orElse(null);
            List<ContentChangeEvent> events = contentChangeDetector.detectTvChange(previous, fresh.get(), LocalDate.now());

            events.forEach(e -> notifyWatchers(content, e));
            saveSeriesState(content, previous, fresh.get());
            return null;
        });
    }

    private void notifyWatchers(Content content, ContentChangeEvent event) {
        List<UUID> userIds = event.type() == NotificationType.NEW_EPISODE
                ? diaryEntryRepository.findUserIdsWatchingSeries(content.getTmdbId())
                : watchlistEntryRepository.findUserIdsByContentId(content.getId());

        LocalDateTime now = LocalDateTime.now();
        userIds.forEach(userId -> notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(event.type())
                .message(buildMessage(content, event))
                .content(content)
                .isRead(false)
                .createdAt(now)
                .updatedAt(now)
                .build()));
    }

    private String buildMessage(Content content, ContentChangeEvent event) {
        return switch (event.type()) {
            case RELEASE -> "New release available";
            case ANNOUNCED_DATE -> "Release date announced: " + event.relevantDate();
            case CANCELLED -> "This title was cancelled";
            case RENEWED -> "This series was renewed";
            case NEW_EPISODE -> "New episode available (S" + event.seasonNumber() + "E" + event.episodeNumber() + ")";
            case FOLLOWED_PERSON_NEW_CREDIT -> "New title from someone you follow";
        };
    }

    private void saveMovieState(Content content, TrackedContentState previous, TmdbMovieDetails fresh) {
        LocalDate releaseDate = fresh.releaseDate() == null || fresh.releaseDate().isBlank() ? null : LocalDate.parse(fresh.releaseDate());
        TrackedContentState state = previous != null ? previous : TrackedContentState.builder().content(content).build();
        state.setLastKnownReleaseDate(releaseDate);
        state.setLastKnownStatus(fresh.status());
        state.setLastCheckedAt(LocalDateTime.now());
        trackedContentStateRepository.save(state);
    }

    private void saveSeriesState(Content content, TrackedContentState previous, TmdbTvDetails fresh) {
        TrackedContentState state = previous != null ? previous : TrackedContentState.builder().content(content).build();
        state.setLastKnownStatus(fresh.status());
        if (fresh.nextEpisodeToAir() != null) {
            state.setNextEpisodeAirDate(LocalDate.parse(fresh.nextEpisodeToAir().airDate()));
            state.setNextEpisodeSeasonNumber(fresh.nextEpisodeToAir().seasonNumber());
            state.setNextEpisodeNumber(fresh.nextEpisodeToAir().episodeNumber());
        } else {
            state.setNextEpisodeAirDate(null);
            state.setNextEpisodeSeasonNumber(null);
            state.setNextEpisodeNumber(null);
        }
        state.setLastCheckedAt(LocalDateTime.now());
        trackedContentStateRepository.save(state);
    }
}
```

*Two things to double check while wiring this up:*
1. `newTransactionExecutor.runInNewTransaction` is typed `Supplier<T>` (Task 1's existing `NewTransactionExecutor`) — the lambdas above return `null` as a `Void`-shaped workaround; if that reads awkwardly once written, consider adding a `void`-returning overload to `NewTransactionExecutor` (`runInNewTransaction(Runnable action)`) instead of forcing every caller through `Supplier<Void>` — small enough to decide at implementation time, either works.
2. `processSeries` runs for **every** `SERIES`-type content in a watchlist **and** every in-progress series from the diary — if the same series is both watchlisted and in-progress, `findDistinctTrackedContent()` and `findDistinctInProgressSeriesContent()` both return it, and it gets processed (and TMDB-called) twice in the same run. Dedupe the two lists by `Content.id` before processing to keep the "one call per distinct title per cycle" guarantee from the spec.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=ContentTrackingJobTest"`
Expected: PASS (4 tests, after fixing the dedup gap called out above and confirming the test list reflects it).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/notification/tracking/ContentTrackingJob.java src/main/java/com/watchwise/watchwise_api/watchlist/repository/WatchlistEntryRepository.java src/main/java/com/watchwise/watchwise_api/diaryentry/repository/DiaryEntryRepository.java src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/test/java/com/watchwise/watchwise_api/notification/tracking/ContentTrackingJobTest.java
git commit -m "feat(notification): add daily ContentTrackingJob"
```

---

## Task 8: `FollowedPersonTrackingJob` (weekly)

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/notification/tracking/FollowedPersonTrackingJob.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/followedperson/repository/FollowedPersonRepository.java`
- Modify: `src/main/resources/application-dev.properties`
- Modify: `src/main/resources/application-prod.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/notification/tracking/FollowedPersonTrackingJobTest.java`

**Interfaces:**
- Consumes: `TmdbClient.getPersonCombinedCredits` (Task 1), `TrackedPersonStateRepository`/`TrackedPersonCreditRepository` (Task 3), `ContentService.getOrCreateReference` (existing), `NotificationRepository`.
- Produces: `FollowedPersonTrackingJob.run(): void`; new query `FollowedPersonRepository.findDistinctPersonTmdbIds(): List<String>`, `FollowedPersonRepository.findUserIdsByPersonTmdbId(String): List<UUID>`.

- [ ] **Step 1: Add queries to `FollowedPersonRepository`**

```java
    @Query("SELECT DISTINCT f.personTmdbId FROM FollowedPerson f")
    List<String> findDistinctPersonTmdbIds();

    @Query("SELECT f.user.id FROM FollowedPerson f WHERE f.personTmdbId = :personTmdbId")
    List<UUID> findUserIdsByPersonTmdbId(@Param("personTmdbId") String personTmdbId);
```

- [ ] **Step 2: Add the weekly cron property**

Append to `application-dev.properties` and `application-prod.properties`:

```properties
app.followed-person-tracking.cron=0 0 5 * * MON
```

- [ ] **Step 3: Write the failing test**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonCredits;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonCreditRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonStateRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowedPersonTrackingJobTest {

    @Mock private FollowedPersonRepository followedPersonRepository;
    @Mock private TrackedPersonStateRepository trackedPersonStateRepository;
    @Mock private TrackedPersonCreditRepository trackedPersonCreditRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentService contentService;
    @Mock private TmdbClient tmdbClient;
    @Mock private NewTransactionExecutor newTransactionExecutor;

    @InjectMocks
    private FollowedPersonTrackingJob followedPersonTrackingJob;

    @Captor
    private ArgumentCaptor<Notification> notificationCaptor;

    private UUID followerId;

    @BeforeEach
    void setUp() {
        followerId = UUID.randomUUID();
        lenient().when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    @DisplayName("[run] Should Notify Followers Of A New Credit - When A Credit Not Seen Before Appears")
    void shouldNotifyFollowersOfANewCreditWhenACreditNotSeenBeforeAppears() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        TrackedPersonState state = TrackedPersonState.builder().id(UUID.randomUUID()).personTmdbId("6193").build();
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), "603")).thenReturn(false);
        ContentRefDTO contentRef = new ContentRefDTO(UUID.randomUUID(), "603", ContentType.MOVIE, null, null, null, null, null, null, null, null, null);
        when(contentService.getOrCreateReference(any())).thenReturn(contentRef);
        when(followedPersonRepository.findUserIdsByPersonTmdbId("6193")).thenReturn(List.of(followerId));

        followedPersonTrackingJob.run();

        verify(notificationRepository).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getType()).isEqualTo(NotificationType.FOLLOWED_PERSON_NEW_CREDIT);
        assertThat(notificationCaptor.getValue().getPersonTmdbId()).isEqualTo("6193");
    }

    @Test
    @DisplayName("[run] Should Not Notify - When The Credit Was Already Seen Before")
    void shouldNotNotifyWhenTheCreditWasAlreadySeenBefore() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        TrackedPersonState state = TrackedPersonState.builder().id(UUID.randomUUID()).personTmdbId("6193").build();
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.of(state));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(
                new TmdbPersonCredits(List.of(new TmdbCredit("603", "movie")), List.of())));
        when(trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), "603")).thenReturn(true);

        followedPersonTrackingJob.run();

        verify(notificationRepository, never()).save(any());
        verify(contentService, never()).getOrCreateReference(any());
    }

    @Test
    @DisplayName("[run] Should Create A TrackedPersonState - When The Person Has Never Been Checked Before")
    void shouldCreateATrackedPersonStateWhenThePersonHasNeverBeenCheckedBefore() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        when(trackedPersonStateRepository.findByPersonTmdbId("6193")).thenReturn(Optional.empty());
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.of(new TmdbPersonCredits(List.of(), List.of())));

        followedPersonTrackingJob.run();

        ArgumentCaptor<TrackedPersonState> stateCaptor = ArgumentCaptor.forClass(TrackedPersonState.class);
        verify(trackedPersonStateRepository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getPersonTmdbId()).isEqualTo("6193");
    }

    @Test
    @DisplayName("[run] Should Skip The Person - When TMDB Returns Nothing")
    void shouldSkipThePersonWhenTmdbReturnsNothing() {
        when(followedPersonRepository.findDistinctPersonTmdbIds()).thenReturn(List.of("6193"));
        when(tmdbClient.getPersonCombinedCredits("6193")).thenReturn(Optional.empty());

        followedPersonTrackingJob.run();

        verify(trackedPersonStateRepository, never()).findByPersonTmdbId(any());
        verify(notificationRepository, never()).save(any());
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `mvnw.cmd test "-Dtest=FollowedPersonTrackingJobTest"`
Expected: FAIL — class doesn't exist.

- [ ] **Step 5: Write `FollowedPersonTrackingJob`**

```java
package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonCredits;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonCredit;
import com.watchwise.watchwise_api.notification.entity.TrackedPersonState;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonCreditRepository;
import com.watchwise.watchwise_api.notification.repository.TrackedPersonStateRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class FollowedPersonTrackingJob {

    private final FollowedPersonRepository followedPersonRepository;
    private final TrackedPersonStateRepository trackedPersonStateRepository;
    private final TrackedPersonCreditRepository trackedPersonCreditRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ContentService contentService;
    private final TmdbClient tmdbClient;
    private final NewTransactionExecutor newTransactionExecutor;

    @Scheduled(cron = "${app.followed-person-tracking.cron}")
    public void run() {
        followedPersonRepository.findDistinctPersonTmdbIds().forEach(this::processPerson);
    }

    private void processPerson(String personTmdbId) {
        try {
            Optional<TmdbPersonCredits> fresh = tmdbClient.getPersonCombinedCredits(personTmdbId);
            if (fresh.isEmpty()) {
                return;
            }

            newTransactionExecutor.runInNewTransaction(() -> {
                TrackedPersonState state = trackedPersonStateRepository.findByPersonTmdbId(personTmdbId)
                        .orElseGet(() -> TrackedPersonState.builder().personTmdbId(personTmdbId).build());

                Stream.concat(fresh.get().cast().stream(), fresh.get().crew().stream())
                        .distinct()
                        .forEach(credit -> processCredit(personTmdbId, state, credit));

                state.setLastCheckedAt(LocalDateTime.now());
                trackedPersonStateRepository.save(state);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Failed to process followed person {}: {}", personTmdbId, e.getMessage());
        }
    }

    private void processCredit(String personTmdbId, TrackedPersonState state, TmdbCredit credit) {
        if (state.getId() != null
                && trackedPersonCreditRepository.existsByTrackedPersonStateIdAndCreditTmdbId(state.getId(), credit.id())) {
            return;
        }

        ContentType creditType = "movie".equals(credit.mediaType()) ? ContentType.MOVIE : ContentType.SERIES;
        ContentRefDTO contentRef = contentService.getOrCreateReference(
                new ContentRefCreationDTO(credit.id(), creditType, null, null, null, null, null, null, null, null, null));

        notifyFollowers(personTmdbId, contentRef);

        if (state.getId() != null) {
            trackedPersonCreditRepository.save(TrackedPersonCredit.builder()
                    .trackedPersonState(state)
                    .creditTmdbId(credit.id())
                    .creditType(creditType)
                    .build());
        }
    }

    private void notifyFollowers(String personTmdbId, ContentRefDTO contentRef) {
        List<UUID> followerIds = followedPersonRepository.findUserIdsByPersonTmdbId(personTmdbId);
        LocalDateTime now = LocalDateTime.now();

        followerIds.forEach(userId -> notificationRepository.save(Notification.builder()
                .user(userRepository.getReferenceById(userId))
                .type(NotificationType.FOLLOWED_PERSON_NEW_CREDIT)
                .message("New title from someone you follow")
                .content(contentService.getContentReference(contentRef.id()))
                .personTmdbId(personTmdbId)
                .isRead(false)
                .createdAt(now)
                .updatedAt(now)
                .build()));
    }
}
```

*Three things to resolve while wiring this up (do not skip — they affect correctness, not just style):*
1. `state.getId() != null` is used above as "is this a brand-new state we haven't saved yet" — but a **newly-built** `TrackedPersonState` via `.builder()...build()` has no `@GeneratedValue` id assigned until it's actually persisted (Lombok's `@Builder` doesn't call `@PrePersist`/DB sequence logic). This means for a first-ever check, `state.getId()` is `null` for every credit in the loop, so **no** `TrackedPersonCredit` rows get saved on the first run, only on the second run onward (since by then `state` has been persisted with an id from the previous run's `trackedPersonStateRepository.save(state)`). Decide explicitly: either (a) save the (possibly-new) `state` **before** the credit loop so it always has an id, or (b) accept that first-run credits are notified-but-not-recorded and therefore get **re-notified** on the very next run (since they'd still look "new" against an empty credit set) — pick (a), it's a one-line reorder (move `trackedPersonStateRepository.save(state)` before the `Stream.concat(...)` loop) and avoids duplicate notifications on the second run.
2. `contentService.getContentReference(contentRef.id())` doesn't exist on `ContentService` today — check its current interface; either add a `getContentReference(UUID contentId): Content` method there (returning the JPA reference, e.g. via `contentRepository.getReferenceById(id)`) or, simpler, extend `ContentService.getOrCreateReference` to also expose the `Content` entity (not just its DTO) since `Notification.content` needs the entity, not `ContentRefDTO`.
3. `Stream.concat(...).distinct()` on `TmdbCredit` records relies on `equals()`/`hashCode()` (records generate both from all components) — two `TmdbCredit`s with the same `id` but different `mediaType` would NOT be deduplicated by `.distinct()`; TMDB doesn't reuse the same numeric id across a movie and a tv show, so this is safe in practice, but note it's an assumption, not a guarantee enforced in code.

- [ ] **Step 6: Fix the three issues above, then run tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=FollowedPersonTrackingJobTest"`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/notification/tracking/FollowedPersonTrackingJob.java src/main/java/com/watchwise/watchwise_api/followedperson/repository/FollowedPersonRepository.java src/main/resources/application-dev.properties src/main/resources/application-prod.properties src/test/java/com/watchwise/watchwise_api/notification/tracking/FollowedPersonTrackingJobTest.java
git commit -m "feat(notification): add weekly FollowedPersonTrackingJob"
```

---

## Task 9: Documentation updates

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/database-schema.html`
- Modify: `CLAUDE.md`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Update `openapi.yaml`**

Replace the `Notification` schema's `type` enum (`[RELEASE, CANCELLATION, DELAY]`) with `[RELEASE, ANNOUNCED_DATE, CANCELLED, RENEWED, NEW_EPISODE, FOLLOWED_PERSON_NEW_CREDIT]`, add a nullable `personTmdbId: { type: string }` property. Change `GET /notifications`'s response from a bare array to the paginated envelope (`allOf` with `PageMeta`, `content` array of `Notification`), matching every other list endpoint's shape, and add `page`/`size` query parameters alongside the existing `isRead` one.

- [ ] **Step 2: Update `database-schema.html`**

Add `notifications`, `tracked_content_states`, `tracked_person_states`, `tracked_person_credits` to the diagram/table list, with a note that the latter three are internal tracking tables (not part of the original PT logical model) used only by the notification background jobs — never exposed via API.

- [ ] **Step 3: Update `CLAUDE.md`**

In the "Avoid" section's `Content` metadata-exceptions paragraph, add one sentence documenting `TrackedContentState`/`TrackedPersonState`/`TrackedPersonCredit` as tracking-only caches, explicitly separate from `Content`, never exposed via API, existing purely to let the two `@Scheduled` jobs detect day-over-day TMDB diffs.

- [ ] **Step 4: Update `business-rules.md`**

Add a `Notification` section documenting: the `RENEWED` heuristic (status `Ended`/`Canceled` → `Returning Series`, since TMDB has no literal "renewed" status); the diff table from the design spec's §6; the one-call-covers-multiple-notification-types design (`/movie/{id}`/`/tv/{id}` responses feed several notification types from a single TMDB call); the per-item-transaction resilience rule.

- [ ] **Step 5: Update `business-rules-summary.md`**

Mirror the new `business-rules.md` entries at summary-level, per `[[feedback_business_rules_summary_sync]]`.

- [ ] **Step 6: Update `progress.md`**

Add today's (or the actual implementation day's) entry describing what was built — `TmdbClient`, `ContentTrackingJob`/`FollowedPersonTrackingJob`, `Notification` + its two tracking cache tables, `GET /notifications`/`PATCH /notifications/{id}/read` — once Tasks 1-8 are actually merged, not before (per `[[feedback_progress_md_scope]]`, only log shipped code).

- [ ] **Step 7: No git commit for this task**

Both `docs/` (`.gitignore:5`) and `CLAUDE.md` itself (`.gitignore:4`) are gitignored in this repository, and neither is currently tracked by git (`git ls-files` returns nothing for either) — confirmed by running `git check-ignore -v CLAUDE.md docs/context/business-rules.md` before starting this plan. That means **every file this task edits is untracked**: `git add` on any of them is either a silent no-op or an explicit "paths are ignored" error, and there is nothing to commit. Skip the git step entirely for this task — the edits still land on disk (any local tooling or future session that reads them sees the update), they just never enter git history. Do not run `git add -f` to force them in; that would change this project's established convention (doc files stay local-only) without the user's explicit sign-off.

---

## Post-plan checklist (from `CLAUDE.md`'s "Recurring bug patterns")

Before calling any task in this plan done, re-check it against `CLAUDE.md`'s five recurring-bug categories:

1. **Exception handling gaps** — `NotificationController`'s two endpoints only throw `NotFoundException`/`ForbiddenException`, both already mapped by `GlobalExceptionHandler`. No new exception type is introduced by this feature.
2. **Unsafe concurrency** — covered by Task 7/8's `NewTransactionExecutor` usage; the two jobs never run concurrently with each other over the same row (different tables), and each job processes items sequentially within a single scheduled invocation (no manual thread pool), so there is no intra-job race to solve.
3. **Cross-feature side effects implemented twice** — N/A; this feature does not touch the watchlist/dropped/diary mutual-exclusion rule.
4. **Missing field validation on new DTOs** — `NotificationResponseDTO` is response-only (no request DTO with client-supplied fields to validate); double check this holds if a future request DTO is added to this feature.
5. **Security rule applied to one endpoint, forgotten on its sibling** — both `GET /notifications` and `PATCH /notifications/{id}/read` require authentication by the project's default (`anyRequest().authenticated()`); `PATCH` additionally needs the ownership check written in Task 6 — confirm the integration test in Task 6 Step 9 actually exercises the CSRF-missing case for `PATCH` (it's the only mutating endpoint in this feature) before considering Task 6 done.
