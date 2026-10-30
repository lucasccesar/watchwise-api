### Task 8: Full verification and final review

All implementation commits through `52ef4c9` are present. Documentation updates are ignored worktree files under docs/context. Do not change production code or docs in this task.

- [ ] **Step 1: Run the focused daily-game suite.**

Run:
`mvnw.cmd test "-Dtest=DailyGameControllerTest,DailyGameControllerIntegrationTest,DailyGameSearchServiceImplTest,DailyGameServiceImplTest,DailyGameServiceImplIntegrationTest,DailyChallengeResponseAssemblerTest,DailyChallengeGeneratorTest,DailyChallengeGenerationServiceImplTest,DailyGameRankingServiceImplTest,DailyGameGenerationJobTest,DailyGameRepositoryTest,DailyGameAttemptDetailsCleanupServiceImplTest,DailyGameAttemptDetailsCleanupJobTest,DailyGameInfoComparisonServiceTest,DailyGameFilmographyServiceTest"`

Expected: exit code 0 and no failures; Docker/Testcontainers is required for repository/integration tests.

- [ ] **Step 2: Run the full Maven suite.**

Run: `mvnw.cmd test`

Expected: exit code 0. If Docker is unavailable, report exact repository/integration tests that could not run.

- [ ] **Step 3: Run clean package verification.**

Run: `mvnw.cmd clean package`

Expected: exit code 0 with Flyway/Hibernate validating V63 and the JSONB entity mapping.

- [ ] **Step 4: Inspect final state and security checklist.** Confirm no open response contains the secret, invalid attempts do not mutate state, give-up is locked transactionally, cleanup is UTC-scoped, answers are not duplicated per user, no unrelated files are staged, and docs/context has the required route/rule updates.

- [ ] **Step 5: Write the report here:**
`C:\Users\Lucas C\.codex\worktrees\daily-games-v2\watchwise-api\.superpowers\sdd\2026-09-29-daily-games-v2-plan\task-8-report.md`

Include exact commands, exit codes, test counts, Docker status, and any remaining limitation. Do not commit or push.
