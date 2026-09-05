# Top Watch Companions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `topWatchCompanions` field (top 3 people the user watched content with, by `WatchCompanion` tag count) to `GET /users/{userId}/summary/month`, `/summary/year`, and `/summary/all-time`.

**Architecture:** Two new JPQL aggregate queries on `WatchCompanionRepository` (one date-range-scoped for month/year, one type-set-scoped for all-time), grouped by companion user id and counted. `SummaryServiceImpl` resolves the companion user ids to `User` entities and maps them to the existing `UserPreviewDTO` via the existing `UserMapper`. No new entity, no new endpoint, no schema/table changes — this only reads the existing `watch_companions` table.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA (JPQL `@Query` with interface projections and `Pageable`), MapStruct (`UserMapper`, already has the needed method), JUnit 5 + Mockito.

## Global Constraints

- No code comments (`CLAUDE.md` § Code conventions) — self-explanatory names only.
- Test method naming: `should<ExpectedBehavior>When<Condition>`, camelCase, no underscores.
- Test `@DisplayName`: `"[methodUnderTest] Should <Expected Behavior> - When <Condition>"`.
- Commit messages: Conventional Commits, one short line, no body, no `Co-Authored-By` trailer, no "e.g./such as/for example/like", no "for X/to support Y/so that Z" justification wording.
- `docs/context/openapi.yaml`, `docs/context/business-rules.md`, `docs/context/business-rules-summary.md`, and `docs/context/progress.md` must be updated in the same change as the code (per `CLAUDE.md`), not as a follow-up.
- Run the full test suite (`mvnw.cmd test`) before considering any task done — do not rely on running only the new test class, since a malformed JPQL string only fails at Spring context bootstrap (caught by any `@SpringBootTest`, not by a plain Mockito unit test).

---

### Task 1: Month/Year in Review — `topWatchCompanions`

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/summary/dto/WatchCompanionCountDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/WatchCompanionRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/MonthInReviewResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/YearInReviewResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `UserMapper.userToUserPreviewDto(User user): UserPreviewDTO` (existing, `com.watchwise.watchwise_api.user.mapper.UserMapper`). `UserRepository.findAllById(Iterable<UUID> ids): List<User>` (existing).
- Produces: `WatchCompanionCountDTO(UserPreviewDTO companion, long watchCount)`. `WatchCompanionRepository.CompanionWatchCount` projection (`getCompanionUserId(): UUID`, `getCount(): Long`). `WatchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(UUID userId, ContentType contentType, LocalDate start, LocalDate end, Pageable pageable): List<CompanionWatchCount>` — reused by Task 2's tests as the reference pattern (Task 2 adds a second, all-time-scoped method on the same repository, `countGroupedByCompanionUserIdAndContentTypeIn`).

- [ ] **Step 1: Create `WatchCompanionCountDTO`**

```java
package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

public record WatchCompanionCountDTO(UserPreviewDTO companion, long watchCount) {
}
```

- [ ] **Step 2: Add the date-range-scoped query and projection to `WatchCompanionRepository`**

Replace the full file content with:

```java
package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WatchCompanionRepository extends JpaRepository<WatchCompanion, UUID> {

    @Query("SELECT wc FROM WatchCompanion wc JOIN FETCH wc.user WHERE wc.diaryEntry.id IN :diaryEntryIds")
    List<WatchCompanion> findByDiaryEntryIdIn(@Param("diaryEntryIds") Collection<UUID> diaryEntryIds);

    @Transactional
    @Modifying
    @Query("DELETE FROM WatchCompanion wc WHERE wc.diaryEntry.id = :diaryEntryId")
    void deleteByDiaryEntryId(@Param("diaryEntryId") UUID diaryEntryId);

    @Query("""
            SELECT wc.user.id AS companionUserId, COUNT(wc) AS count
            FROM WatchCompanion wc
            WHERE wc.diaryEntry.user.id = :userId
            AND wc.diaryEntry.content.type = :contentType
            AND wc.diaryEntry.watchedDate BETWEEN :start AND :end
            GROUP BY wc.user.id
            ORDER BY COUNT(wc) DESC
            """)
    List<CompanionWatchCount> countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
            @Param("userId") UUID userId, @Param("contentType") ContentType contentType,
            @Param("start") LocalDate start, @Param("end") LocalDate end, Pageable pageable);

    interface CompanionWatchCount {
        UUID getCompanionUserId();
        Long getCount();
    }

}
```

- [ ] **Step 3: Add `topWatchCompanions` to `MonthInReviewResponseDTO` and `YearInReviewResponseDTO`**

In `MonthInReviewResponseDTO.java`, append the field to the record and import the DTO is unnecessary (same package):

```java
public record MonthInReviewResponseDTO(
        List<DiaryEntryResponseDTO> recentWatched,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<RatingCountDTO> ratingsDistribution,
        long watchCount,
        long minutesWatched,
        LocalDate firstWatchedDate,
        LocalDate lastWatchedDate,
        List<DailyMinutesDTO> minutesPerDay,
        List<DayOfWeekCountDTO> watchCountByDayOfWeek,
        List<GenreCountDTO> genreCounts,
        List<SeriesWatchTimeDTO> topSeriesByWatchTime,
        List<ContentRefDTO> topLongestMovies,
        List<WatchCompanionCountDTO> topWatchCompanions) {
}
```

In `YearInReviewResponseDTO.java`:

```java
public record YearInReviewResponseDTO(
        List<RatingCountDTO> ratingsDistribution,
        long watchCount,
        long minutesWatched,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<MonthCountDTO> watchCountByMonth,
        List<DayOfWeekCountDTO> watchCountByDayOfWeek,
        LocalDate firstWatchedDate,
        LocalDate lastWatchedDate,
        List<LongestWatchedItemDTO> longestWatched,
        List<GenreCountDTO> genreCounts,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<WatchCompanionCountDTO> topWatchCompanions) {
}
```

- [ ] **Step 4: Write the failing tests in `SummaryServiceImplTest`**

Add two new `@Mock` fields right after the existing `top5EntryRepository` mock (around line 93):

```java
    @Mock
    private WatchCompanionRepository watchCompanionRepository;

    @Mock
    private UserMapper userMapper;
```

Add the matching imports near the top of the file:

```java
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.summary.dto.WatchCompanionCountDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
```

Add a helper method next to `genreCount`/`scoreCount` (around line 717):

```java
    private WatchCompanionRepository.CompanionWatchCount companionWatchCount(UUID companionUserId, long count) {
        return new WatchCompanionRepository.CompanionWatchCount() {
            @Override
            public UUID getCompanionUserId() {
                return companionUserId;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }
```

Add two new tests, right after `shouldPromoteTop5MembersFirstWhenRankingTopRatedForMonthInReview` (around line 549):

```java
    @Test
    @DisplayName("[getMonthInReview] Should Return Top Watch Companions Scoped By Type And Month")
    void shouldReturnTopWatchCompanionsForMonthInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        User marina = buildUser(marinaId, true);
        when(watchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.MOVIE), any(), any(), any()))
                .thenReturn(List.of(companionWatchCount(marinaId, 5L)));
        when(userRepository.findAllById(List.of(marinaId))).thenReturn(List.of(marina));
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(new UserPreviewDTO(marinaId, "marina", null, true));

        MonthInReviewResponseDTO result = summaryService.getMonthInReview(lucasId, lucasId, ContentType.MOVIE, YearMonth.of(2026, 8));

        assertThat(result.topWatchCompanions()).hasSize(1);
        assertThat(result.topWatchCompanions().getFirst().companion().id()).isEqualTo(marinaId);
        assertThat(result.topWatchCompanions().getFirst().watchCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("[getYearInReview] Should Return Top Watch Companions Scoped By Type And Year")
    void shouldReturnTopWatchCompanionsForYearInReview() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        User marina = buildUser(marinaId, true);
        when(watchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                eq(lucasId), eq(ContentType.EPISODE), eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)), any()))
                .thenReturn(List.of(companionWatchCount(marinaId, 12L)));
        when(userRepository.findAllById(List.of(marinaId))).thenReturn(List.of(marina));
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(new UserPreviewDTO(marinaId, "marina", null, true));

        YearInReviewResponseDTO result = summaryService.getYearInReview(lucasId, lucasId, ContentType.SERIES, 2026);

        assertThat(result.topWatchCompanions()).hasSize(1);
        assertThat(result.topWatchCompanions().getFirst().companion().id()).isEqualTo(marinaId);
        assertThat(result.topWatchCompanions().getFirst().watchCount()).isEqualTo(12L);
    }
```

- [ ] **Step 5: Run the new tests to verify they fail**

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest#shouldReturnTopWatchCompanionsForMonthInReview+shouldReturnTopWatchCompanionsForYearInReview"`
Expected: FAIL — `result.topWatchCompanions()` does not exist yet (compile error), or once the DTO field compiles, `NullPointerException`/empty list because `SummaryServiceImpl` doesn't populate it yet.

- [ ] **Step 6: Wire the computation into `SummaryServiceImpl`**

Add imports:

```java
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.summary.dto.WatchCompanionCountDTO;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
```

Add the constant next to `TOP_LONGEST_MOVIES_LIMIT` (around line 77):

```java
    private static final int TOP_COMPANIONS_LIMIT = 3;
```

Add the two new dependencies next to `top5EntryRepository` (around line 90):

```java
    private final WatchCompanionRepository watchCompanionRepository;
    private final UserMapper userMapper;
```

Add two private helper methods next to `computeLongestWatched` (around line 411):

```java
    private List<WatchCompanionCountDTO> computeTopWatchCompanions(UUID userId, ContentType contentType, LocalDate start, LocalDate end) {
        List<WatchCompanionRepository.CompanionWatchCount> rows = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween(
                        userId, contentType, start, end, PageRequest.of(0, TOP_COMPANIONS_LIMIT));
        return toWatchCompanionCountDtos(rows);
    }

    private List<WatchCompanionCountDTO> toWatchCompanionCountDtos(List<WatchCompanionRepository.CompanionWatchCount> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, User> usersById = userRepository
                .findAllById(rows.stream().map(WatchCompanionRepository.CompanionWatchCount::getCompanionUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));
        return rows.stream()
                .map(row -> new WatchCompanionCountDTO(
                        userMapper.userToUserPreviewDto(usersById.get(row.getCompanionUserId())), row.getCount()))
                .toList();
    }
```

In `getMonthInReview`, right before the final `return new MonthInReviewResponseDTO(...)` (around line 239), add:

```java
        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanions(userId, watchedContentType, start, end);
```

and append `topWatchCompanions` as the last constructor argument:

```java
        return new MonthInReviewResponseDTO(recentWatched, topRated, bottomRated, ratingsDistribution, watchCount,
                minutesWatched, firstWatchedDate, lastWatchedDate, minutesPerDay, watchCountByDayOfWeek, genreCounts,
                topSeriesByWatchTime, topLongestMovies, topWatchCompanions);
```

In `getYearInReview`, right before the final `return new YearInReviewResponseDTO(...)` (around line 302), add:

```java
        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanions(userId, watchedContentType, start, end);
```

and append `topWatchCompanions` as the last constructor argument:

```java
        return new YearInReviewResponseDTO(ratingsDistribution, watchCount, minutesWatched, averageMinutesPerMonth,
                averageMinutesPerWeek, averageMinutesPerDay, watchCountByMonth, watchCountByDayOfWeek,
                firstWatchedDate, lastWatchedDate, longestWatched, genreCounts, topRated, bottomRated, topWatchCompanions);
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`
Expected: PASS — every test in the class, including the two new ones.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/summary/dto/WatchCompanionCountDTO.java src/main/java/com/watchwise/watchwise_api/summary/dto/MonthInReviewResponseDTO.java src/main/java/com/watchwise/watchwise_api/summary/dto/YearInReviewResponseDTO.java src/main/java/com/watchwise/watchwise_api/diaryentry/repository/WatchCompanionRepository.java src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
git commit -m "feat(summary): add topWatchCompanions to month and year in review"
```

---

### Task 2: All Time Stats — `topWatchCompanions`

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/diaryentry/repository/WatchCompanionRepository.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/dto/AllTimeStatsResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java`
- Test: `src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java`

**Interfaces:**
- Consumes: `WatchCompanionCountDTO`, `WatchCompanionRepository.CompanionWatchCount`, `toWatchCompanionCountDtos(List<CompanionWatchCount>): List<WatchCompanionCountDTO>` — all from Task 1.
- Produces: `WatchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeIn(UUID userId, Collection<ContentType> contentTypes, Pageable pageable): List<CompanionWatchCount>`.

- [ ] **Step 1: Write the failing test**

Add to `SummaryServiceImplTest`, right after `shouldReturnTotalMovieAndEpisodeCountsFromTheRepository` (around line 632):

```java
    @Test
    @DisplayName("[getAllTimeStats] Should Return Top Watch Companions Combining Movies And Episodes")
    void shouldReturnTopWatchCompanionsForAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        User marina = buildUser(marinaId, true);
        when(watchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeIn(
                eq(lucasId), eq(Set.of(ContentType.MOVIE, ContentType.EPISODE)), any()))
                .thenReturn(List.of(companionWatchCount(marinaId, 37L)));
        when(userRepository.findAllById(List.of(marinaId))).thenReturn(List.of(marina));
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(new UserPreviewDTO(marinaId, "marina", null, true));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.topWatchCompanions()).hasSize(1);
        assertThat(result.topWatchCompanions().getFirst().companion().id()).isEqualTo(marinaId);
        assertThat(result.topWatchCompanions().getFirst().watchCount()).isEqualTo(37L);
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Return Empty Top Watch Companions - When User Has No Companions Tagged")
    void shouldReturnEmptyTopWatchCompanionsWhenNoneTaggedForAllTimeStats() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        AllTimeStatsResponseDTO result = summaryService.getAllTimeStats(lucasId, lucasId);

        assertThat(result.topWatchCompanions()).isEmpty();
    }
```

Add `import java.util.Set;` to the test file's imports if not already present (it is not — `SummaryServiceImplTest` currently has no `java.util.Set` import).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest#shouldReturnTopWatchCompanionsForAllTimeStats+shouldReturnEmptyTopWatchCompanionsWhenNoneTaggedForAllTimeStats"`
Expected: FAIL — compile error (`AllTimeStatsResponseDTO.topWatchCompanions()` and `WatchCompanionRepository.countGroupedByCompanionUserIdAndContentTypeIn` don't exist yet).

- [ ] **Step 3: Add the all-time query to `WatchCompanionRepository`**

Add this method and keep everything from Task 1 unchanged:

```java
    @Query("""
            SELECT wc.user.id AS companionUserId, COUNT(wc) AS count
            FROM WatchCompanion wc
            WHERE wc.diaryEntry.user.id = :userId
            AND wc.diaryEntry.content.type IN :contentTypes
            GROUP BY wc.user.id
            ORDER BY COUNT(wc) DESC
            """)
    List<CompanionWatchCount> countGroupedByCompanionUserIdAndContentTypeIn(
            @Param("userId") UUID userId, @Param("contentTypes") Collection<ContentType> contentTypes,
            Pageable pageable);
```

Place it right after `countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween` and before the `CompanionWatchCount` interface declaration.

- [ ] **Step 4: Add `topWatchCompanions` to `AllTimeStatsResponseDTO`**

```java
public record AllTimeStatsResponseDTO(
        long totalMoviesWatched,
        long totalEpisodesWatched,
        long totalMinutesWatched,
        long totalTheaterVisits,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<YearCountDTO> watchCountByYearMovies,
        List<YearCountDTO> watchCountByYearEpisodes,
        List<DecadeCountDTO> watchCountByDecade,
        List<CountryCountDTO> watchCountByCountry,
        List<ContentWatchCountDTO> mostLoggedContent,
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsEpisodes,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<WatchCompanionCountDTO> topWatchCompanions) {
}
```

- [ ] **Step 5: Wire it into `SummaryServiceImpl.getAllTimeStats`**

Add a helper method next to `computeTopWatchCompanions` (added in Task 1):

```java
    private List<WatchCompanionCountDTO> computeTopWatchCompanionsAllTime(UUID userId) {
        List<WatchCompanionRepository.CompanionWatchCount> rows = watchCompanionRepository
                .countGroupedByCompanionUserIdAndContentTypeIn(
                        userId, Set.of(ContentType.MOVIE, ContentType.EPISODE), PageRequest.of(0, TOP_COMPANIONS_LIMIT));
        return toWatchCompanionCountDtos(rows);
    }
```

In `getAllTimeStats`, right before `return new AllTimeStatsResponseDTO(...)` (around line 355), add:

```java
        List<WatchCompanionCountDTO> topWatchCompanions = computeTopWatchCompanionsAllTime(userId);
```

and append `topWatchCompanions` as the last constructor argument:

```java
        return new AllTimeStatsResponseDTO(totalMoviesWatched, totalEpisodesWatched, totalMinutesWatched, totalTheaterVisits,
                averageMinutesPerMonth, averageMinutesPerWeek, averageMinutesPerDay,
                watchCountByYearMovies, watchCountByYearEpisodes, watchCountByDecade, watchCountByCountry,
                mostLoggedContent, genreCountsMovies, genreCountsEpisodes, topRated, bottomRated, topWatchCompanions);
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvnw.cmd test "-Dtest=SummaryServiceImplTest"`
Expected: PASS — every test in the class.

- [ ] **Step 7: Run the full suite to catch any JPQL bootstrap error**

Run: `mvnw.cmd test`
Expected: PASS — full suite green (this is the only step that actually validates both new `@Query` strings parse correctly, since that only happens at Spring context startup).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/watchwise/watchwise_api/diaryentry/repository/WatchCompanionRepository.java src/main/java/com/watchwise/watchwise_api/summary/dto/AllTimeStatsResponseDTO.java src/main/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImpl.java src/test/java/com/watchwise/watchwise_api/summary/service/impl/SummaryServiceImplTest.java
git commit -m "feat(summary): add topWatchCompanions to all time stats"
```

---

### Task 3: Docs

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/business-rules-summary.md`
- Modify: `docs/context/progress.md`

**Interfaces:**
- Consumes: nothing new — this task only documents the shape and behavior already implemented and tested in Tasks 1–2.
- Produces: nothing consumed by later tasks — this is the last task.

- [ ] **Step 1: Update `openapi.yaml`**

In the `MonthInReview` schema (around line 2662), add after the `topLongestMovies` property:

```yaml
        topWatchCompanions:
          type: array
          description: >
            Top 3 pessoas que o usuário assistiu com (WatchCompanion), por quantidade de tags no
            mês, escopado pelo type pedido (mesma regra de EPISODE-para-SERIES do resto da
            resposta). Cada rewatch marcado conta separadamente.
          items:
            type: object
            properties:
              companion: { $ref: "#/components/schemas/UserPreviewDTO" }
              watchCount: { type: integer }
```

In the `YearInReview` schema (around line 2696), add after `bottomRated`:

```yaml
        topWatchCompanions:
          type: array
          description: Mesma regra de MonthInReview.topWatchCompanions, escopado pro ano pedido.
          items:
            type: object
            properties:
              companion: { $ref: "#/components/schemas/UserPreviewDTO" }
              watchCount: { type: integer }
```

In the `AllTimeStats` schema (around line 2734), add after `bottomRated`:

```yaml
        topWatchCompanions:
          type: array
          description: >
            Mesma regra de MonthInReview.topWatchCompanions, sem escopo de type nem de data —
            combina MOVIE e SERIES (via EPISODE), all-time.
          items:
            type: object
            properties:
              companion: { $ref: "#/components/schemas/UserPreviewDTO" }
              watchCount: { type: integer }
```

- [ ] **Step 2: Update `business-rules.md`**

In the `## Summary` section (around line 1547, right before `## Feed`), add a new bullet:

```markdown
- **`topWatchCompanions` (Month/Year/All Time) reaproveita as tags de `WatchCompanion` já
  existentes, não infere overlap entre diários** (`SummaryServiceImpl.computeTopWatchCompanions`/
  `computeTopWatchCompanionsAllTime`, `WatchCompanionRepository.
  countGroupedByCompanionUserIdAndContentTypeAndWatchedDateBetween`/
  `countGroupedByCompanionUserIdAndContentTypeIn`) — "seguidores" aqui significa pessoas que o
  dono segue, não quem o segue, porque `WatchCompanion` só permite marcar a primeira direção (ver
  § DiaryEntry, "Assistido com"). Top 3, contando cada tag (rewatches incluídos), sem desempate
  explícito no 3º lugar (mesmo padrão de `mostLoggedContent`/`topSeriesByWatchTime`, só `ORDER BY
  count DESC`). Month/Year escopam por `type` (`MOVIE`/`EPISODE`, mesma conversão SERIES→EPISODE
  do resto da resposta) e pela janela de data já calculada pro resto do endpoint; All Time combina
  `MOVIE`+`EPISODE` sem filtro de data, mesmo padrão de `watchCountByDecade`/`watchCountByCountry`.
  SEASON/SERIES (marcadores automáticos de conclusão) nunca entram, porque o filtro de tipo usado é
  sempre `MOVIE`/`EPISODE`, nunca `SEASON`/`SERIES`. Companion que o dono deixou de seguir depois de
  marcar continua contando — a regra de "só quem você segue" vale só na criação da tag
  (`DiaryEntryServiceImpl.validateCompanions`), não retroativamente. Sem checagem de privacidade
  nova — quem já pode ver o summary já podia ver cada `DiaryEntry.watchedWith` individualmente.
```

- [ ] **Step 3: Update `business-rules-summary.md`**

In the `## Summary` section (around line 205, right before `## Feed`), add:

```markdown
- `topWatchCompanions` (Month/Year/All Time) reaproveita as tags de `WatchCompanion` já existentes (não infere overlap entre diários) — "seguidores" aqui é quem o dono segue, não quem o segue, já que `WatchCompanion` só permite essa direção. Top 3 por quantidade de tags (rewatches contam), sem desempate explícito. Month/Year escopam por `type`+data do resto da resposta; All Time combina MOVIE+EPISODE sem filtro de data. SEASON/SERIES nunca entram. Companion desseguido depois continua contando — a regra vale só na criação da tag.
```

- [ ] **Step 4: Update `progress.md`**

At the end of the file, after the last `## 2026-09-02 (8) — ...` entry, add:

```markdown

## 2026-09-02 (9) — `topWatchCompanions` em Month/Year in Review e All Time Stats

Novo campo `topWatchCompanions` (top 3, com quantidade) nas três respostas de summary escopadas por
período/all-time — pedido do usuário. Reaproveita as tags de `WatchCompanion` ("assistido com") já
existentes em vez de inferir overlap entre diários; como `WatchCompanion` só permite marcar quem o
próprio dono segue, "seguidores" aqui significa pessoas que o usuário segue, não quem o segue.
`WatchCompanionRepository` ganhou duas queries novas — uma escopada por `type`+intervalo de datas
(Month/Year in Review), outra combinando `MOVIE`+`EPISODE` sem data (All Time Stats) — agrupando por
companion e contando tags (rewatches incluídos), sem desempate explícito no 3º lugar, mesmo padrão
de `mostLoggedContent`. Novo DTO `WatchCompanionCountDTO` reaproveita `UserPreviewDTO`, o mesmo já
usado em `DiaryEntry.watchedWith`. 4 testes novos em `SummaryServiceImplTest`; suíte completa: 2118
testes, sem falhas. `openapi.yaml`/`business-rules.md`/`business-rules-summary.md` atualizados
junto.
```

Adjust the "suíte completa: N testes" number to whatever the actual final count is after running `mvnw.cmd test` in Task 2 Step 7 — 2118 assumes 2114 (last known count from the most recent `progress.md` entry) + 4 new tests; verify against the real output instead of trusting this arithmetic.

- [ ] **Step 5: Run the full suite one more time**

Run: `mvnw.cmd test`
Expected: PASS — confirms the docs change didn't touch any code path and the suite is still green.

- [ ] **Step 6: Commit**

```bash
git add docs/context/openapi.yaml docs/context/business-rules.md docs/context/business-rules-summary.md docs/context/progress.md
git commit -m "docs: document topWatchCompanions in summary endpoints"
```

Note: `docs/` is gitignored in this repository (per prior project convention) — if `git add` reports these paths as ignored, this commit step is a no-op and should be skipped; the doc files are still updated on disk, just not tracked by git.
