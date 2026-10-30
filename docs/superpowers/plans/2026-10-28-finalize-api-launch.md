# Finalize API Launch Implementation Plan

**Goal:** Consolidate eligible code onto `main`, complete the requested launch flows, and keep all `docs/` changes local-only.

**Architecture:** Keep the existing Spring Boot/PostgreSQL structure. Reuse the email-confirmation flow, add a Resend-backed sender, add one-time password-reset tokens and an owner-scoped sent-follow-requests read, and keep the diary deletion contract aligned with the current event/projection model. A deletion-impact preview is unnecessary because deleting a real event invalidates derived projections without cascading to other diary events. Treat `docs/` as an ignored local workspace.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Flyway, Spring `RestClient`, Resend REST API.

**Status (2026-10-28):** Implemented and pushed to `main`. `mvnw.cmd -DskipTests compile` passed;
automated tests were not run in this consolidation. Local documentation and OpenAPI remain ignored by Git.

## Global Constraints

- Do not stage or push anything under `docs/`.
- Preserve existing local documentation while removing its unpushed documentation-only commit from `main` history.
- Preserve all existing commits without `Co-Authored-By`; create new commits without co-authorship trailers.
- Keep one API replica until Gate R10 has operational evidence.
- Do not run the test suite in this pass; compile and inspect the final Git state before pushing.
- The Resend API key and verified sender address must come from environment configuration.

---

## Tasks

1. **Inventory Git state and local docs**
   - Keep `docs/` present locally and ignored by the restored `.gitignore`.
   - Identify all unmerged commits, dirty worktree changes, and detached worktree commits.
   - Check commit messages for co-authorship trailers before integration.

2. **Consolidate existing code onto `main`**
   - Integrate the seven episode-rating commits only after reconciling their implementation with current summary endpoints.
   - Integrate the ten audit-remediation commits and the existing email-confirmation worktree changes.
   - Preserve existing migrations and test files from those changes.

3. **Implement Resend delivery**
   - Add a Resend REST adapter behind the existing email sender interface.
   - Configure API key, sender address, and enablement through environment properties.
   - Send confirmation and reset messages without logging raw tokens.

4. **Implement password recovery**
   - Add a generic request endpoint that does not reveal whether an account exists.
   - Store only a hash of a short-lived, single-use reset token.
   - On confirmation, update the password and invalidate existing sessions.

5. **Implement sent follow requests**
   - Add a paginated endpoint scoped to the authenticated user and pending outgoing requests only.

6. **Align the diary deletion contract**
   - Remove the obsolete deletion-impact route and `overrideProtectedEntries` from the OpenAPI contract.
   - Document that deleting a diary event removes only that MOVIE/EPISODE event and invalidates derived projections.

7. **Update local documentation only**
   - Merge OpenAPI routes for imports, reviews, ratings, diary view, email confirmation, password recovery, and sent follows.
   - Correct stale `/search` and migration-count statements.
   - Keep all updated `docs/` files ignored and local.

8. **Finalize production configuration and Git state**
   - Add Resend production environment placeholders without storing secrets.
   - Compile without running tests, inspect the code diff, ensure only intended files are staged, and verify every outgoing commit lacks a `Co-Authored-By` trailer.
   - Push only the code commits to `origin/main`; remove worktrees and local branches only after their changes are preserved on `main`.
