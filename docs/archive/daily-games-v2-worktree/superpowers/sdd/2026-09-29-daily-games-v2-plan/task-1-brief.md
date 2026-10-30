### Task 1: Persist only current-day attempt details and clean them at UTC midnight

**Files:**
- Create: `src/main/resources/db/migration/V63__add-daily-game-attempt-details.sql`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/entity/UserDailyGameResult.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/repository/UserDailyGameResultRepository.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCleanupService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameAttemptDetailsCleanupServiceImpl.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameAttemptDetailsCleanupJob.java`
- Modify: `src/main/resources/application-dev.properties`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/repository/DailyGameRepositoryTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameAttemptDetailsCleanupServiceImplTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/tracking/DailyGameAttemptDetailsCleanupJobTest.java`

**Interfaces:**
- `UserDailyGameResult.attemptDetails` is a nullable `Map<String, Object>` mapped with `@JdbcTypeCode(SqlTypes.JSON)`.
- `UserDailyGameResultRepository.clearAttemptDetailsBefore(LocalDate currentDate)` updates only rows joined to challenges with `challenge_date < currentDate`.
- `DailyGameAttemptDetailsCleanupService.cleanup(LocalDate currentDate)` is transactional and delegates to the repository.

- [ ] **Step 1: Write the failing JSONB/repository tests.** Assert that the entity exposes a nullable JSONB map, the cleanup query clears expired challenge rows, and leaves current-day rows untouched.

```java
@Test
void shouldClearAttemptDetailsOnlyBeforeTheCurrentGmtDate() {
    int cleared = repository.clearAttemptDetailsBefore(LocalDate.of(2026, 9, 30));

    assertThat(cleared).isEqualTo(1);
    assertThat(expiredResult.getAttemptDetails()).isNull();
    assertThat(currentResult.getAttemptDetails()).containsKey("attempts");
}
```

- [ ] **Step 2: Run the focused repository test and confirm RED.**

Run: `mvnw.cmd test "-Dtest=DailyGameRepositoryTest#shouldClearAttemptDetailsOnlyBeforeTheCurrentGmtDate"`

Expected: FAIL because `attempt_details`, the entity field and cleanup query do not exist yet.

- [ ] **Step 3: Add the migration and entity mapping.** Use:

```sql
ALTER TABLE user_daily_game_results
    ADD COLUMN attempt_details JSONB;
```

Add the nullable field with `@JdbcTypeCode(SqlTypes.JSON)`, `@Column(columnDefinition = "jsonb")` and a setter so the locked service can replace the map.

- [ ] **Step 4: Add repository cleanup, service and UTC job.** Configure `app.daily-games.attempt-details-cleanup.cron=0 5 0 * * *` and schedule with `zone = "UTC"`; the job passes `LocalDate.now(clock)` to the service. The JSONB codec is introduced with the shared DTOs in Task 2.

- [ ] **Step 5: Run the focused tests and confirm GREEN.**

Run: `mvnw.cmd test "-Dtest=DailyGameRepositoryTest,DailyGameAttemptDetailsCleanupServiceImplTest,DailyGameAttemptDetailsCleanupJobTest"`

- [ ] **Step 6: Commit the code migration and lifecycle support.**

```bash
git add src/main/resources/db/migration/V63__add-daily-game-attempt-details.sql src/main/java/com/watchwise/watchwise_api/dailygame src/main/resources/application-dev.properties src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): retain current-day attempt details"
```

After the commit, verify that its message contains no `Co-Authored-By` trailer or other self-attribution.
