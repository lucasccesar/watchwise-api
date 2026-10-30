# Daily Games v2 Task 5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build normalized Movie by Info and Series by Info snapshots and typed comparison feedback with the exact thresholds from `task-5-brief.md`.

**Architecture:** Keep generation responsible for fetching full challenge details and freezing normalized secret metadata in the existing JSONB answer snapshot. Keep comparison in a dedicated service that fetches the validated guessed movie/series and compares its normalized fields against the challenge snapshot. Extend only the existing attempt pipeline and response assembly needed for info feedback; preserve episode, person, poster, hints, scoring, locking, and redaction behavior.

**Tech Stack:** Java 21, Spring Boot, Maven wrapper, JUnit 5, Mockito, AssertJ, existing TMDB client and JSONB attempt-details codec.

## Global Constraints

- Use the exact thresholds and statuses in `task-5-brief.md`.
- Do not implement actor filmography behavior.
- Candidate validation must happen before attempts are incremented or details appended.
- TMDB unavailable must not consume attempts.
- Do not leak open-state secrets or duplicate answer storage.
- Preserve current-day JSONB storage and all non-info flows.
- Use TDD RED -> GREEN, Maven wrapper, and a Conventional Commit without `Co-Authored-By`.
- Do not push.

### Task 1: Establish failing comparison contract tests

**Files:**
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`

- [ ] Write unit tests for set equality/partial/no-overlap, BR certification, director and creator rules, cast counts, year direction/tolerance, revenue 10%/30% thresholds, season direction, and `NO_DATA`.
- [ ] Add generator assertions proving Movie/Series info answer snapshots contain normalized lists and numeric values while retaining null omission and existing hints.
- [ ] Run `mvnw.cmd test "-Dtest=DailyGameInfoComparisonServiceTest,DailyChallengeGeneratorTest"` and record the expected RED output in the report.

### Task 2: Normalize Movie/Series info snapshots

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`

- [ ] Build normalized detail maps from full TMDB responses, using trimmed nonblank values and preserving case for display while comparisons normalize case-insensitively.
- [ ] Keep Movie revenue distinct from Series season count; include BR certification/providers and retain existing hint types/order.
- [ ] Keep answer snapshots terminal-only and display snapshots limited to image data.

### Task 3: Implement info comparator

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameInfoComparisonService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceImpl.java`

- [ ] Implement `DailyGameInfoFeedbackDTO compare(DailyChallenge challenge, DailyGameCandidateIdentity candidate)`.
- [ ] Fetch only the candidate's full movie/series details, propagate TMDB unavailable/not-found semantics through validation, and compare the candidate with the challenge's frozen snapshot.
- [ ] Implement exact status/direction/display/matched-count semantics without revealing the secret value in an open attempt.
- [ ] Run focused comparison tests and make them GREEN.

### Task 4: Integrate feedback and preserve redaction

**Files:**
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Modify: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`

- [ ] Inject and invoke the comparator after candidate validation and before attempt details are appended.
- [ ] Store typed `infoFeedback` in current-day attempt details and return it for current-day attempt/state reads, without changing historical storage or non-info feedback.
- [ ] Keep the complete answer snapshot available only for terminal responses.
- [ ] Add tests for info attempt feedback, current-day exposure, open redaction, terminal answer, validation ordering, and TMDB-unavailable attempt preservation.

### Task 5: Verify, self-review, report, and commit

**Files:**
- Create: `.superpowers/sdd/2026-09-29-daily-games-v2-plan/task-5-report.md`

- [ ] Run the focused suite from the brief and capture complete RED/GREEN command output and exit statuses.
- [ ] Run a self-review for exception handling, concurrency/side effects, answer leakage, duplicate storage, and sibling-flow regressions.
- [ ] Inspect the diff, commit only code/tests with `feat(daily-game): compare info game guesses`, verify the commit has no co-authorship trailer, and record the hash.
- [ ] Leave documentation/report files uncommitted unless explicitly required by the user.
