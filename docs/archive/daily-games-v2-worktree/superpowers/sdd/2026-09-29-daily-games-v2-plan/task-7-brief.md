### Task 7: Synchronize the public contract and project context documentation

Tasks 1–6 are implemented through commit `52ef4c9`. The worktree now contains copies of the existing ignored context docs under `docs/context`; update those copies and do not commit documentation-only changes.

**Files:**
- Modify: `docs/context/openapi.yaml`
- Modify: `docs/context/database-schema.md`
- Modify: `docs/context/database-schema.html`
- Modify: `docs/context/business-rules.md`
- Modify: `docs/context/progress.md`

- [ ] **Step 1: Update OpenAPI paths.** Document specific state routes, both give-up routes, the three episode selection routes, removal of episode `q` search, `majorRoles`, attempt details, comparison cells, redacted filmographies, episode series metadata and terminal answer fields.

- [ ] **Step 2: Update schema documentation.** Add nullable `attempt_details JSONB` to `user_daily_game_results`, explain that it is populated only for the current GMT date and cleared after midnight, and keep the single-copy `daily_challenges.answer_snapshot` rule.

- [ ] **Step 3: Update business rules.** Record give-up semantics, six-still generation, hierarchical episode selection, info comparison thresholds, `NO_DATA`, actor intersection behavior and the proportional Major roles formula. Remove stale rules that say no attempt details are ever stored or that episode selection still uses text search.

- [ ] **Step 4: Append shipped changes to the current `2026-09-29` section in `progress.md`.** Do not add a next-steps section or log design-only decisions.

- [ ] **Step 5: Run documentation consistency searches.**

Run: `rg -n "search/episodes|attempt_details|give-up|majorRoles|six still|filmography|NO_DATA" docs/context/openapi.yaml docs/context/database-schema.md docs/context/database-schema.html docs/context/business-rules.md docs/context/progress.md`

Expected: every new route/rule appears in the contract and no stale `/search/episodes?...&q=` behavior remains documented.
