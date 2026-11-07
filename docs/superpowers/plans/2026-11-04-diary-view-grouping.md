# Diary View Grouping Implementation Plan

**Goal:** Implement the approved grouped diary view.
**Architecture:** PostgreSQL window functions assign contiguous runs after filtering; pagination operates on runs. A separate summary DTO preserves individual JSON.
**Tech Stack:** Java 21, Spring JDBC, PostgreSQL, Maven.

- [x] Add grouped response DTO and backwards compatible service overload.
- [x] Detect SQL run boundaries by series, watch number and effective date, preserving current ordering. Aggregate only episode events, with scoped recovery for type=SEASON.
- [x] Route grouped requests through SQL pagination; bypass grouping for hasReview=true. Batch hydrate singleton and series references, preserve access checks.
- [x] Add repository tests for interruptions, dates, passes, metrics, unknown runtimes, pagination and completion scopes. Run focused Maven tests.
- [x] Synchronize OpenAPI, business rules and chronological progress. Review diff, compile and commit code only.
