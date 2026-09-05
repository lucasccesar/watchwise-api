# Top 3 Watch Companions — design

## Goal

Add a "top 3 followers watched the most content with" stat to the summary/stats endpoints,
with the count.

## Decisions

- **Data source**: reuse the existing `WatchCompanion` ("assistido com") tags — not an inferred
  overlap between two users' diaries. `WatchCompanion` already restricts tagging to users the
  owner follows with `Follower.status = ACCEPTED`, so "seguidores" here means people the user
  follows (the only direction `WatchCompanion` supports), not people who follow the user.
- **Count basis**: total tags, including repeated tags across rewatches of the same content (not
  distinct-content count).
- **Placement**: no new field on `UserResponseDTO`. New field `topWatchCompanions` added to three
  existing summary responses instead: `MonthInReviewResponseDTO`, `YearInReviewResponseDTO`,
  `AllTimeStatsResponseDTO`.

## Shape

```java
public record WatchCompanionCountDTO(UserPreviewDTO companion, long watchCount) {}
```

Reuses `UserPreviewDTO` (id, username, profilePicture, isProfilePublic) — the same DTO already
used for `DiaryEntry.watchedWith` — instead of introducing a new user-reference shape.

Added as `List<WatchCompanionCountDTO> topWatchCompanions` to:
- `MonthInReviewResponseDTO`
- `YearInReviewResponseDTO`
- `AllTimeStatsResponseDTO`

## Scoping per endpoint

- `summary/month`, `summary/year`: companions counted from `WatchCompanion` rows whose
  `DiaryEntry` matches the requested `type` (MOVIE, or SERIES → EPISODE, same
  `watchedContentTypeFor` mapping already used for every other field on these endpoints) and
  whose `watchedDate` falls within the requested month/year — same `start`/`end` window already
  computed for the rest of the endpoint.
- `summary/all-time`: no `type` param on this endpoint already, so companions are combined across
  MOVIE + EPISODE (mirrors how `watchCountByDecade`/`watchCountByCountry` already combine both),
  no date filter.

In all three, only MOVIE/EPISODE content types are counted — SEASON/SERIES auto-generated
completion entries are excluded, consistent with how every other aggregate in this file already
excludes those synthetic markers from counts (see `CLAUDE.md` → Avoid → `runtimeMinutes`).

Limited to top 3 (new constant `TOP_COMPANIONS_LIMIT = 3` in `SummaryServiceImpl`).

## Query

New JPQL queries on `WatchCompanionRepository`, grouping by companion user id, ordered by count
descending, limited via `Pageable`:

```java
interface CompanionWatchCount {
    UUID getCompanionUserId();
    Long getCount();
}

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

`SummaryServiceImpl` resolves the returned companion user ids to `User` entities via
`UserRepository.findAllById` and maps each to `UserPreviewDTO` via the existing
`UserMapper.userToUserPreviewDto` (same mapper already used for `watchedWith`). Requires adding
`WatchCompanionRepository` and `UserMapper` as new dependencies of `SummaryServiceImpl`.

## Edge cases (deliberately following existing patterns, no new behavior invented)

- **Tie at 3rd place**: no explicit tie-break — matches `mostLoggedContent`/`topSeriesByWatchTime`,
  which also only `ORDER BY count DESC` with no secondary sort.
- **Fewer than 3 companions**: list has 0–3 items, no error — same as every other "top N" list in
  these responses.
- **Companion no longer followed**: still counted. The "must currently follow, status=ACCEPTED"
  rule only applies at tag-creation time (`DiaryEntryServiceImpl.validateCompanions`); an existing
  `WatchCompanion` row is immutable once created, so an unfollow afterward doesn't retroactively
  remove it from this count.
- **Privacy**: no new check. Whoever can already view the summary (owner, or an accepted follower
  of the target when their profile is private) could already see each `DiaryEntry.watchedWith` —
  this only aggregates data that was already visible to them.

## Docs to update alongside the code change

- `docs/context/openapi.yaml`: add `topWatchCompanions` to the `MonthInReview`, `YearInReview`,
  and `AllTimeStats` schemas, `items: { type: object, properties: { companion: { $ref:
  "#/components/schemas/UserPreviewDTO" }, watchCount: { type: integer } } }`.
- `docs/context/business-rules.md`: new entry under the existing WatchCompanion section
  documenting this aggregation and its scoping/edge-case rules.
- `docs/context/progress.md`: entry for today under the existing chronological log.
