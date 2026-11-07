# Calendar Response Backdrop Implementation Plan

> **For agentic workers:** Inline execution in this session; the user's feature request authorizes implementation.

**Goal:** Add the first returned calendar event's TMDB backdrop URL to the calendar response envelope.

**Architecture:** Keep calendar event ordering and event DTOs unchanged. `CalendarServiceImpl` resolves exactly one metadata record for the first event via the existing cached `TmdbCardMetadataResolver`, mapping episodes, seasons, and series to their parent series. Missing backdrop metadata produces `null` without changing calendar schedule errors.

**Tech Stack:** Java 21, Spring Boot, records, existing TMDB metadata cache, OpenAPI YAML.

## Global Constraints

- Use the existing TMDB metadata resolver and `TmdbImageUrlBuilder.backdropUrl`.
- Do not add persisted fields or migrations.
- Keep `backdropPath` nullable and preserve the monthly schedule's existing failure behavior.
- Update OpenAPI, `business-rules.md`, and `progress.md` with the code change.
- Do not add or run tests unless the user asks to test or verify implementation.

---

### Task 1: Resolve the envelope backdrop and document the contract

**Files:**
- Modify `src/main/java/com/watchwise/watchwise_api/calendar/dto/CalendarResponseDTO.java` to add nullable `backdropPath`, retaining the existing three-argument constructor for current callers.
- Modify `src/main/java/com/watchwise/watchwise_api/calendar/service/impl/CalendarServiceImpl.java` to derive the backdrop from the first assembled event using `TmdbCardMetadataResolver`; use a movie coordinate for movies and a series coordinate for all TV event types.
- Modify `docs/context/openapi.yaml` to describe nullable top-level `backdropPath` as a `w1280` URL from the first returned event.
- Modify `docs/context/business-rules.md` to record the event-type source, cache use, and null fallback.
- Append the completed behavior under `2026-11-02` in `docs/context/progress.md`.

**Interfaces:**
- Consumes `TmdbCardMetadataResolver.resolve(ContentCoordinate, String)` and `TmdbImageUrlBuilder.backdropUrl(String)`.
- Produces `CalendarResponseDTO(month, region, events, backdropPath)` with the legacy three-argument overload retained.

- [x] Add the nullable envelope field while preserving existing construction sites.
- [x] Resolve metadata only for the first event; map `MOVIE` to its movie ID and `EPISODE`/`SEASON`/`SERIES` to the parent series ID.
- [x] Keep the backdrop optional: no events, no metadata, or no backdrop yields `null`.
- [x] Update the OpenAPI schema and the two required project records.
- [x] Run `mvnw.cmd -DskipTests compile` and `graphify update .`; inspect the final diff and working tree.
