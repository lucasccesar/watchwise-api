# Final Daily Games v2 fix wave

## Scope

Apply one reviewed fix wave for the final whole-branch review of
`c648904..2565b54`. Work only in the Daily Games v2 worktree and keep the
existing Tasks 1–8 intact except where needed to address the findings below.

## Findings to address

1. `DailyGameServiceImpl.submitAttempt` computes `infoFeedback` and
   `filmographyFeedback`, but historical responses suppress all attempt
   details and expose no newly calculated attempt. The response must expose
   the current submitted `DailyGameAttemptDTO` independently of the
   current-day transient JSONB history. Preserve secret redaction and keep
   historical results free of prior attempt history.
2. `ActorBySeriesFilmographyGenerator` accepts a candidate with two total
   series entries even when the default `majorRoles=true` projection removes
   every entry because the shared lookup budget left `totalEpisodes` null.
   Generation must reject such an actor and require at least two valid
   default-mode Major works, while retaining the complete snapshot for the
   all-roles toggle.
3. `DailyGameFilmographyServiceImpl.compare` creates an effectively
   unlimited lookup budget and is called while `DailyGameServiceImpl` holds
   the pessimistic user-result lock and transaction. Bound the lookup budget
   by the configured maximum and move TMDB-backed candidate validation and
   feedback calculation out of the transaction/lock. Re-fetch and lock the
   result only for the short persistence/update/response section; re-check
   terminal status and attempts after the lock so concurrent submissions keep
   the existing invariant.
4. Strengthen the give-up response assertions with exact terminal answer
   fields where the existing fixtures allow it.
5. Remove the accidentally committed root `final-fix-report.md`; it is a
   review artifact, not a production or project-context document.

## Constraints

- Use the existing `NewTransactionExecutor` pattern for the isolated
  persistence transaction when appropriate; do not hold a DB lock across
  external TMDB calls.
- Preserve current route shapes, DTO compatibility constructors, answer
  redaction, current-day attempt-details cleanup, and the aggregate endpoint.
- Add regression tests before or alongside implementation for historical
  responses, default Major-role generation after budget exhaustion, bounded
  comparison lookups, and the transaction/lock boundary where practical.
- Update `docs/context/openapi.yaml`, `database-schema.md`,
  `database-schema.html`, `business-rules.md`, and `progress.md` only when
  the behavior/contract changes require it; keep documentation worktree-only.
- Run focused affected tests and the full Maven suite/package needed to
  support the report. Commit code/tests in one Conventional Commit without a
  body or `Co-Authored-By` trailer.

## Report

Append a report to
`.superpowers/sdd/2026-09-29-daily-games-v2-plan/final-fix-wave-report.md`
with changed files, design decisions, exact commands, exit status and test
counts, remaining concerns, and the commit SHA. Return only status, commit,
and one-line test summary in the agent response.
