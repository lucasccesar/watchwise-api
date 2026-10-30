### Task 5: Build structured Movie/Series by Info comparisons

Tasks 1–4 are committed through `6f1b230`. Task 2 already provides typed comparison DTOs and attempt-detail transport; this task gives the Movie/Series by Info games their real normalized snapshots and feedback. Do not implement actor filmography behavior.

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/DailyGameInfoComparisonService.java`
- Create: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/MovieByInfoGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/SeriesByInfoGenerator.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeSnapshotAssembler.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImpl.java`
- Modify: `src/main/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssembler.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/generation/DailyChallengeGeneratorTest.java`
- Create: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameInfoComparisonServiceTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyGameServiceImplTest.java`
- Test: `src/test/java/com/watchwise/watchwise_api/dailygame/service/impl/DailyChallengeResponseAssemblerTest.java`

**Interfaces:**
- `DailyGameInfoFeedbackDTO compare(DailyChallenge challenge, DailyGameCandidateIdentity candidate)`.
- `DailyGameComparisonCellDTO` contains status, optional direction, display value, matched values and match count.
- `DailyGameInfoFeedbackDTO` contains cells for platforms, genres, year, certification, director/creators, cast, production companies and revenue/seasons.

**Comparison rules:**
- Sets: exact equality is `MATCH`; non-empty intersection is `PARTIAL`; empty intersection is `NO_MATCH`.
- Certification and director: exact normalized equality is `MATCH`, different values are `NO_MATCH`.
- Creators: any creator in common is `PARTIAL`; complete set equality is `MATCH`.
- Year: equal is `MATCH`; difference of one year is `PARTIAL`; larger difference is `NO_MATCH`. Include `SECRET_HIGHER` or `SECRET_LOWER` whenever different.
- Revenue: relative difference up to 10% is `MATCH`; up to 30% is `PARTIAL`; above 30% is `NO_MATCH`. Compare against the larger absolute revenue and include secret direction when different.
- Cast: one or two shared names are `PARTIAL`; three or more are `MATCH`.
- Missing TMDB data is `NO_DATA`, never `NO_MATCH`.
- Movie uses revenue; series uses season count. Do not compare a movie revenue cell against a series season cell.

- [ ] **Step 1: Write failing comparison tests for every agreed rule.** Cover exact/partial/no set overlap, BR certification, director/creator overlap, cast counts 1–2 versus 3+, year direction and one-year tolerance, revenue 10%/30% thresholds, seasons direction, and `NO_DATA`.

```java
@Test
void shouldMarkTwoSharedCastMembersAsPartial() {
    DailyGameInfoFeedbackDTO feedback = comparison.compare(challengeWithCast("A", "B", "C"), candidateWithCast("B", "C"));

    assertThat(feedback.cast().status()).isEqualTo(DailyGameComparisonStatus.PARTIAL);
    assertThat(feedback.cast().matchedValues()).containsExactly("B", "C");
}
```

- [ ] **Step 2: Run `mvnw.cmd test "-Dtest=DailyGameInfoComparisonServiceTest"` and confirm RED.**

- [ ] **Step 3: Replace comma-only info snapshots with normalized lists and numeric values.** Preserve null fields, keep movie revenue separate from series season count, retain BR certification/providers, and keep existing hint behavior for modalities that still use hints.

- [ ] **Step 4: Implement the comparator with set normalization and explicit thresholds.** Use case-insensitive trimmed names for equality, calculate relative revenue difference against the larger absolute value, and return `NO_DATA` whenever the required side lacks a value. Use existing TmdbClient full-detail methods for the guessed movie/series and propagate TMDB unavailable errors without consuming attempts.

- [ ] **Step 5: Append typed info feedback to current-day attempt details and expose it in attempt/state responses without leaking the secret.** Terminal answers may reveal the complete normalized snapshot. Preserve the existing episode/person/poster flows.

- [ ] **Step 6: Run focused tests and commit.**

Run: `mvnw.cmd test "-Dtest=DailyChallengeGeneratorTest,DailyGameInfoComparisonServiceTest,DailyGameServiceImplTest,DailyChallengeResponseAssemblerTest"`

```bash
git add src/main/java/com/watchwise/watchwise_api/dailygame src/test/java/com/watchwise/watchwise_api/dailygame
git commit -m "feat(daily-game): compare info game guesses"
```

Confirm no co-authorship trailer was added.
