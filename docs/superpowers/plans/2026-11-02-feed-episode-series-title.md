# Feed Episode Series Title Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the parent series title to episode diary items returned by `GET /feed` while retaining the episode title in `card.title`.

**Architecture:** Extend `FeedItemViewDTO` with a nullable `seriesTitle` and populate it only for `DIARY_ENTRY` items whose content type is `EPISODE`. Add each such item's parent SERIES coordinate to the selected page's card specs, allowing the existing `ContentCardAssembler` resolution to supply the series title without a separate TMDB call.

**Tech Stack:** Java 21, Spring Boot, Jackson records, existing `ContentCardAssembler`, OpenAPI YAML, Markdown project context docs.

## Global Constraints

- Java DTOs remain records and use English field names.
- `card.title` continues to contain the episode title.
- `seriesTitle` is populated only for `DIARY_ENTRY` + `EPISODE`; unavailable parent coordinates or titles result in `null`.
- Resolve metadata through `ContentCardAssembler`; do not persist the series or add a direct TMDB request.
- Keep `docs/context/openapi.yaml`, `docs/context/business-rules.md`, and `docs/context/progress.md` synchronized with the behavior.
- Do not add or run tests unless the user requests testing or verification.
- Do not commit documentation-only changes; if committing the code change, stage only the Java implementation files so unrelated local documentation changes remain unstaged.

---

### Task 1: Return the parent series title for episode diary events

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/dto/FeedItemViewDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/feed/service/impl/FeedServiceImpl.java`
- Modify: `docs/context/openapi.yaml` (`FeedItemView` schema)
- Modify: `docs/context/business-rules.md` (`Feed` section)
- Modify: `docs/context/progress.md` (append to the `2026-11-02` entry)

**Interfaces:**
- `FeedItemViewDTO` gains a nullable `String seriesTitle` response component. Preserve the existing convenience constructor by delegating with `seriesTitle = null`.
- `FeedServiceImpl.cardSpecs(List<FeedItemDTO>)` adds one deduplicated SERIES card spec for each selected `DIARY_ENTRY` episode that has a nonblank `seriesTmdbId`.
- `FeedServiceImpl.toFeedItemView(...)` reads that series card's title and passes it to the DTO only for `DIARY_ENTRY` episodes.

- [x] Add `seriesTitle` to the end of the `FeedItemViewDTO` record components and keep its existing four-argument convenience constructor delegating with a null title.
- [x] Add a focused helper in `FeedServiceImpl` that accepts a `FeedItemDTO` and returns its parent SERIES coordinate only when it is a diary episode with a nonblank `seriesTmdbId`.
- [x] Add the resulting SERIES spec to `cardSpecs` only for `DIARY_ENTRY` episodes; rely on the existing coordinate-keyed map to deduplicate parent series shared by several episodes on the page.
- [x] In `toFeedItemView`, look up the parent SERIES card, take its nonblank `title`, and pass it to the extended DTO constructor only when the event is `DIARY_ENTRY` and its content type is `EPISODE`; preserve the existing episode card and its title.
- [x] Add nullable `seriesTitle` to `FeedItemView` in OpenAPI, describing that it is the parent series title for episode diary entries and null when unavailable.
- [x] Add the feed behavior to the `Feed` rules and append a shipped-behavior summary to the existing `2026-11-02` progress entry.
- [x] Review the source and documentation diff for contract consistency, event/type gating, null behavior, and preservation of `card.title`. Do not add or run tests under the current instruction. Production compilation completed with `mvnw.cmd -DskipTests compile`.
- [x] Commit only the two Java files with a Conventional Commit message; keep documentation files unstaged.
