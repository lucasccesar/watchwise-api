### Task 2: Add typed shared game details, specific reads and give-up

Task 1 already exists in commit `58497c7`: `UserDailyGameResult` has nullable `attemptDetails` JSONB, the current-day cleanup job exists, and attempt-details codec behavior is intentionally added here.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameCandidateDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonStatus.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonDirection.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameComparisonCellDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameInfoFeedbackDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyFeedbackDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyStateDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameFilmographyEntryDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameActorGuessDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameAttemptDetailsCodec.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameStateDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAttemptResponseDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/dto/DailyGameAnswerDTO.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameService.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameController.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/controller/DailyGameControllerIntegrationTest.java`

**Interfaces:**
- Add `DailyGameStateDTO getGame(UUID userId, LocalDate challengeDate, DailyGameType gameType, boolean majorRoles)`.
- Add `DailyGameAttemptResponseDTO giveUp(UUID userId, DailyGameType gameType)` and the historical overload with `LocalDate`.
- Add `DailyGameAttemptDTO(int attemptNumber, DailyGameCandidateDTO candidate, DailyGameGuessFeedbackDTO episodeFeedback, DailyGameInfoFeedbackDTO infoFeedback, DailyGameFilmographyFeedbackDTO filmographyFeedback)`.
- Add `DailyGameCandidateDTO(targetKind, tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber, title, imageUrl, date)`.
- Add `DailyGameAttemptDetailsCodec.read(Map<String,Object>)` returning an immutable list and `append(Map<String,Object>, DailyGameAttemptDTO)` returning a fresh map without mutating caller-owned collections.
- Add nullable `attempts` and nullable `DailyGameFilmographyStateDTO filmography` fields to state/attempt responses while preserving existing constructor overloads.
- Add `GET /games/{gameType}/today`, `GET /games/{challengeDate}/{gameType}`, `POST /games/{gameType}/give-up` and `POST /games/{challengeDate}/{gameType}/give-up`.

- [ ] **Step 1: Write failing service tests for a specific read and give-up.** Cover a `NOT_PLAYED` specific state, a give-up without a prior result, a give-up from `IN_PROGRESS`, no attempt increment, score zero, terminal answer, and `409` on a terminal result.

```java
@Test
void shouldFailWithoutConsumingAnAttemptWhenTheUserGivesUp() {
    DailyGameAttemptResponseDTO response = service().giveUp(USER_ID, DailyGameType.MOVIE_BY_INFO);

    assertThat(response.status()).isEqualTo(DailyGameViewStatus.FAILED);
    assertThat(response.attemptsUsed()).isZero();
    assertThat(response.score()).isZero();
    assertThat(response.answer()).isNotNull();
}
```

- [ ] **Step 2: Run the focused test and confirm RED.**

Run: `mvnw.cmd test "-Dtest=DailyGameServiceImplTest#shouldFailWithoutConsumingAnAttemptWhenTheUserGivesUp"`

Expected: FAIL because no give-up service method or route exists.

- [ ] **Step 3: Implement specific reads and shared response fields.** Reuse existing `getDay` challenge/result loading for one game, require the requested `gameType` exists for the date, and pass `majorRoles` only to the series-filmography projection. Keep open answers redacted. Use the codec to read/append only current-day attempt details; historical responses may expose no transient attempt list.

- [ ] **Step 4: Implement give-up in the same `@Transactional` boundary as attempts.** Insert the result if absent, load it with `findByUserIdAndDailyChallengeIdForUpdate`, reject terminal results with `ConflictException`, set `FAILED`, `score = 0`, leave `attemptsUsed` unchanged, and return the terminal answer from the challenge snapshot.

- [ ] **Step 5: Add controller tests for route precedence, CSRF/rate limiting and `ApiError` conflict responses.** Verify `/games/today` still resolves to the aggregate route and `/games/{gameType}/today` resolves to the specific route.

- [ ] **Step 6: Run focused tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest,DailyGameControllerTest,DailyGameControllerIntegrationTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): add specific game reads and give-up"
```

Confirm no co-authorship trailer was added.
