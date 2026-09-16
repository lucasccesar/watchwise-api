# Final fix report — Picks

Date: 2026-09-16

The final-review wave was closed in this worktree without broad redesign. The existing changes were preserved and committed together with the following final-fix coverage:

- FIXED Pick targets are revalidated against current TMDB existence and the template eligibility period before creation or replacement.
- Fixed-option writes use a flushed persistence helper that translates `uq_picks_template_options_fixed_target` into the documented conflict error, including initial template/category options.
- Pick selection updates and template period changes acquire the template lock in a consistent order.
- External option-search pages preserve the provider total and requested page metadata.
- Null fixed-option entries are rejected during validation and service processing.
- Migration V53 closes the incomplete eligibility-date CHECK constraint.
- Domain integration tests, support fixtures, repository coverage, and focused service assertions are included.

Verification performed:

- `git diff --check`: passed.
- Maven compile was attempted with Java 21. The wrapper launcher failed in the local PowerShell environment; direct Maven then reached compilation after dependency resolution. Compilation was blocked by the worktree's known temporary Search overlay gap (`SearchService` and `SearchType` are absent from this checkout while dependent Search sources remain). No new test suite was run at the user's request.
- Docker/Testcontainers were not exercised; their availability remains unverified.

The temporary local Maven repository created by the attempted compile was removed. No push was performed.
