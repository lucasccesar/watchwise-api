package com.watchwise.watchwise_api.dailygame.repository;

import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallengeHint;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameResultStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DailyGameRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DailyChallengeRepository challengeRepository;

    @Autowired
    private DailyChallengeHintRepository hintRepository;

    @Autowired
    private UserDailyGameResultRepository resultRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        resultRepository.deleteAll();
        hintRepository.deleteAll();
        challengeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("[enum] Should Expose Every Daily Game Type With Its Target And Attempt Limit")
    void shouldExposeEveryDailyGameTypeWithItsTargetAndAttemptLimit() {
        assertThat(DailyGameType.values()).containsExactly(
                DailyGameType.MOVIE_BY_POSTER,
                DailyGameType.SERIES_BY_POSTER,
                DailyGameType.PERSON_BY_FACE,
                DailyGameType.EPISODE_BY_FRAME,
                DailyGameType.MOVIE_BY_INFO,
                DailyGameType.SERIES_BY_INFO,
                DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY,
                DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY);
        assertThat(DailyGameType.MOVIE_BY_POSTER.maxAttempts()).isEqualTo(6);
        assertThat(DailyGameType.SERIES_BY_POSTER.maxAttempts()).isEqualTo(6);
        assertThat(DailyGameType.PERSON_BY_FACE.maxAttempts()).isEqualTo(6);
        assertThat(DailyGameType.EPISODE_BY_FRAME.maxAttempts()).isEqualTo(10);
        assertThat(DailyGameType.MOVIE_BY_INFO.maxAttempts()).isEqualTo(10);
        assertThat(DailyGameType.SERIES_BY_INFO.maxAttempts()).isEqualTo(10);
        assertThat(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY.maxAttempts()).isEqualTo(10);
        assertThat(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY.maxAttempts()).isEqualTo(10);
        assertThat(DailyGameType.MOVIE_BY_POSTER.targetKind()).isEqualTo(DailyGameTargetKind.MOVIE);
        assertThat(DailyGameType.SERIES_BY_POSTER.targetKind()).isEqualTo(DailyGameTargetKind.SERIES);
        assertThat(DailyGameType.PERSON_BY_FACE.targetKind()).isEqualTo(DailyGameTargetKind.PERSON);
        assertThat(DailyGameType.EPISODE_BY_FRAME.targetKind()).isEqualTo(DailyGameTargetKind.EPISODE);
        assertThat(DailyGameType.MOVIE_BY_INFO.targetKind()).isEqualTo(DailyGameTargetKind.MOVIE);
        assertThat(DailyGameType.SERIES_BY_INFO.targetKind()).isEqualTo(DailyGameTargetKind.SERIES);
        assertThat(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY.targetKind()).isEqualTo(DailyGameTargetKind.PERSON);
        assertThat(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY.targetKind()).isEqualTo(DailyGameTargetKind.PERSON);
    }

    @Test
    @DisplayName("[save] Should Persist And Reload JSON Snapshots")
    void shouldPersistAndReloadJsonSnapshots() throws Exception {
        DailyChallenge saved = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        entityManager.clear();

        DailyChallenge reloaded = challengeRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAnswerSnapshot().get("answer").asText()).isEqualTo("Fight Club");
        assertThat(reloaded.getDisplaySnapshot().get("title").asText()).isEqualTo("Fight Club");
    }

    @Test
    @DisplayName("[existsByChallengeDateAndGameType] Should Find Only The Exact Challenge Identity")
    void shouldFindOnlyTheExactChallengeIdentity() {
        LocalDate challengeDate = LocalDate.of(2026, 9, 27);
        DailyGameType gameType = DailyGameType.MOVIE_BY_POSTER;
        challengeRepository.saveAndFlush(buildChallenge(challengeDate, gameType, "movie:550"));

        assertThat(challengeRepository.existsByChallengeDateAndGameType(challengeDate, gameType)).isTrue();
        assertThat(challengeRepository.existsByChallengeDateAndGameType(
                challengeDate.plusDays(1), gameType)).isFalse();
    }

    @Test
    @DisplayName("[existsByGameTypeAndAnswerKey] Should Find Only The Exact Game Answer Identity")
    void shouldFindOnlyTheExactGameAnswerIdentity() {
        DailyGameType gameType = DailyGameType.MOVIE_BY_POSTER;
        challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), gameType, "movie:550"));

        assertThat(challengeRepository.existsByGameTypeAndAnswerKey(gameType, "movie:550")).isTrue();
        assertThat(challengeRepository.existsByGameTypeAndAnswerKey(gameType, "movie:680")).isFalse();
        assertThat(challengeRepository.existsByGameTypeAndAnswerKey(
                DailyGameType.MOVIE_BY_INFO, "movie:550")).isFalse();
    }

    @Test
    @DisplayName("[save] Should Reject An Invalid Game Type Enum Value")
    void shouldRejectAnInvalidGameTypeEnumValue() {
        assertThatThrownBy(() -> insertChallengeRow(
                "NOT_A_GAME", "MOVIE", "550", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_game_type");
    }

    @Test
    @DisplayName("[save] Should Reject An Invalid Target Kind Enum Value")
    void shouldRejectAnInvalidTargetKindEnumValue() {
        assertThatThrownBy(() -> insertChallengeRow(
                "MOVIE_BY_POSTER", "NOT_A_TARGET", "550", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_target_kind");
    }

    @Test
    @DisplayName("[save] Should Reject A Game And Target Kind Mismatch")
    void shouldRejectAGameAndTargetKindMismatch() {
        assertThatThrownBy(() -> insertChallengeRow(
                "MOVIE_BY_POSTER", "SERIES", "550", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_game_target_kind");
    }

    @Test
    @DisplayName("[save] Should Reject A Movie Without Its Target Coordinate")
    void shouldRejectAMovieWithoutItsTargetCoordinate() {
        assertThatThrownBy(() -> insertChallengeRow(
                "MOVIE_BY_POSTER", "MOVIE", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_coordinates");
    }

    @Test
    @DisplayName("[save] Should Reject A Movie With Episode Coordinates")
    void shouldRejectAMovieWithEpisodeCoordinates() {
        assertThatThrownBy(() -> insertChallengeRow(
                "MOVIE_BY_POSTER", "MOVIE", "550", "1396", 1, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_coordinates");
    }

    @Test
    @DisplayName("[save] Should Reject An Episode Without Its Episode Coordinate")
    void shouldRejectAnEpisodeWithoutItsEpisodeCoordinate() {
        assertThatThrownBy(() -> insertChallengeRow(
                "EPISODE_BY_FRAME", "EPISODE", null, "1396", 1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_coordinates");
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Challenge Date And Game Type")
    void shouldRejectDuplicateChallengeDateAndGameType() {
        challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:680")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_daily_challenges_date_game");
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Game Type And Answer Key")
    void shouldRejectDuplicateGameTypeAndAnswerKey() {
        challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 28), DailyGameType.MOVIE_BY_POSTER, "movie:550")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_daily_challenges_game_answer");
    }

    @Test
    @DisplayName("[save] Should Allow Reusing An Answer Key Across Game Types")
    void shouldAllowReusingAnAnswerKeyAcrossGameTypes() {
        challengeRepository.save(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "shared:550"));
        DailyChallenge second = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_INFO, "shared:550"));

        assertThat(challengeRepository.findByChallengeDateAndGameType(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_INFO))
                .contains(second);
    }

    @Test
    @DisplayName("[save] Should Reject Episode Coordinates With A Non-Positive Number")
    void shouldRejectEpisodeCoordinatesWithANonPositiveNumber() {
        DailyChallenge invalid = buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.EPISODE_BY_FRAME, "episode:1396:1:1")
                .toBuilder()
                .seasonNumber(0)
                .build();

        assertThatThrownBy(() -> challengeRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenges_positive_coordinates");
    }

    @Test
    @DisplayName("[findByDailyChallengeIdOrderByPositionAsc] Should Return Hints In Position Order")
    void shouldReturnHintsInPositionOrder() {
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        hintRepository.save(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(3)
                .hintType("YEAR")
                .hintValue("1999")
                .build());
        hintRepository.save(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(1)
                .hintType("GENRE")
                .hintValue("Drama")
                .build());
        hintRepository.saveAndFlush(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(2)
                .hintType("DIRECTOR")
                .hintValue("David Fincher")
                .build());

        assertThat(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId()))
                .extracting(DailyChallengeHint::getPosition)
                .containsExactly(1, 2, 3);
        assertThat(hintRepository.findByDailyChallengeIdInOrderByDailyChallengeIdAscPositionAsc(
                List.of(challenge.getId())))
                .extracting(DailyChallengeHint::getPosition)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("[save] Should Reject A Zero Hint Position")
    void shouldRejectAZeroHintPosition() {
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> hintRepository.saveAndFlush(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(0)
                .hintType("YEAR")
                .hintValue("1999")
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenge_hints_positive_position");
    }

    @Test
    @DisplayName("[save] Should Reject A Negative Hint Position")
    void shouldRejectANegativeHintPosition() {
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> hintRepository.saveAndFlush(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(-1)
                .hintType("YEAR")
                .hintValue("1999")
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_daily_challenge_hints_positive_position");
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Hint Positions For A Challenge")
    void shouldRejectDuplicateHintPositionsForAChallenge() {
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        hintRepository.saveAndFlush(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(1)
                .hintType("YEAR")
                .hintValue("1999")
                .build());

        assertThatThrownBy(() -> hintRepository.saveAndFlush(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(1)
                .hintType("GENRE")
                .hintValue("Drama")
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_daily_challenge_hints_position");
    }

    @Test
    @DisplayName("[delete] Should Cascade Delete Hints When Challenge Is Deleted")
    void shouldCascadeDeleteHintsWhenChallengeIsDeleted() {
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        hintRepository.saveAndFlush(DailyChallengeHint.builder()
                .dailyChallenge(challenge)
                .position(1)
                .hintType("YEAR")
                .hintValue("1999")
                .build());

        challengeRepository.deleteById(challenge.getId());
        challengeRepository.flush();
        entityManager.clear();

        assertThat(hintRepository.findByDailyChallengeIdOrderByPositionAsc(challenge.getId())).isEmpty();
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate User Result For A Challenge")
    void shouldRejectDuplicateUserResultForAChallenge() {
        User user = userRepository.saveAndFlush(buildUser());
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 12, 0);
        resultRepository.saveAndFlush(buildResult(user, challenge, now));

        assertThatThrownBy(() -> resultRepository.saveAndFlush(buildResult(user, challenge, now)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_daily_game_results_user_challenge");
    }

    @Test
    @DisplayName("[save] Should Reject Negative Attempts")
    void shouldRejectNegativeAttempts() {
        User user = userRepository.saveAndFlush(buildUser());
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> insertResultRow(
                UUID.randomUUID(), user.getId(), challenge.getId(), -1, 0, "IN_PROGRESS"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_daily_game_results_non_negative");
    }

    @Test
    @DisplayName("[save] Should Reject Negative Score")
    void shouldRejectNegativeScore() {
        User user = userRepository.saveAndFlush(buildUser());
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> insertResultRow(
                UUID.randomUUID(), user.getId(), challenge.getId(), 0, -1, "IN_PROGRESS"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_daily_game_results_non_negative");
    }

    @Test
    @DisplayName("[save] Should Reject An Invalid Result Status")
    void shouldRejectAnInvalidResultStatus() {
        User user = userRepository.saveAndFlush(buildUser());
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));

        assertThatThrownBy(() -> insertResultRow(
                UUID.randomUUID(), user.getId(), challenge.getId(), 0, 0, "NOT_A_STATUS"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_daily_game_results_status");
    }

    @Test
    @DisplayName("[insertIfAbsent] Should Ignore A Concurrently Existing User Result")
    void shouldIgnoreAConcurrentlyExistingUserResult() {
        User user = userRepository.saveAndFlush(buildUser());
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 12, 0);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        assertThat(resultRepository.insertIfAbsent(firstId, user.getId(), challenge.getId(), now)).isEqualTo(1);
        assertThat(resultRepository.insertIfAbsent(secondId, user.getId(), challenge.getId(), now)).isZero();

        UserDailyGameResult result = resultRepository
                .findByUserIdAndDailyChallengeIdForUpdate(user.getId(), challenge.getId())
                .orElseThrow();
        assertThat(result.getId()).isEqualTo(firstId);
        assertThat(result.getStatus()).isEqualTo(DailyGameResultStatus.IN_PROGRESS);
        assertThat(result.getAttemptsUsed()).isZero();
        assertThat(result.getScore()).isZero();
        assertThat(resultRepository.findByUserIdAndDailyChallengeIdIn(
                user.getId(), List.of(challenge.getId())))
                .extracting(UserDailyGameResult::getId)
                .containsExactly(firstId);
    }

    @Test
    @DisplayName("[delete] Should Cascade Delete User Results When User Is Deleted")
    void shouldCascadeDeleteUserResultsWhenUserIsDeleted() {
        User user = userRepository.saveAndFlush(buildUser());
        DailyChallenge challenge = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        UserDailyGameResult result = resultRepository.saveAndFlush(buildResult(
                user, challenge, LocalDateTime.of(2026, 9, 27, 12, 0)));

        userRepository.deleteById(user.getId());
        userRepository.flush();
        entityManager.clear();

        assertThat(resultRepository.findById(result.getId())).isEmpty();
    }

    @Test
    @DisplayName("[findByChallengeDate] Should Order Challenges By Date And Game Type")
    void shouldOrderChallengesByDateAndGameType() {
        DailyChallenge laterSeries = challengeRepository.save(buildChallenge(
                LocalDate.of(2026, 9, 28), DailyGameType.SERIES_BY_POSTER, "series:1396"));
        DailyChallenge earlierMovie = challengeRepository.save(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:550"));
        DailyChallenge earlierInfo = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_INFO, "movie:680"));

        assertThat(challengeRepository.findByChallengeDateOrderByGameTypeAsc(LocalDate.of(2026, 9, 27)))
                .extracting(DailyChallenge::getId)
                .containsExactly(earlierInfo.getId(), earlierMovie.getId());
        assertThat(challengeRepository.findByChallengeDateBetweenOrderByChallengeDateDescGameTypeAsc(
                LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28)))
                .extracting(DailyChallenge::getId)
                .containsExactly(laterSeries.getId(), earlierInfo.getId(), earlierMovie.getId());
    }

    @Test
    @DisplayName("[findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc] Should Exclude Current Date And Preserve Stable Ordering - When History Is Queried")
    void shouldExcludeCurrentDateAndPreserveStableOrderingWhenHistoryIsQueried() {
        DailyChallenge today = challengeRepository.save(buildChallenge(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "movie:today"));
        DailyChallenge olderMovie = challengeRepository.save(buildChallenge(
                LocalDate.of(2026, 9, 26), DailyGameType.MOVIE_BY_POSTER, "movie:older"));
        DailyChallenge olderInfo = challengeRepository.saveAndFlush(buildChallenge(
                LocalDate.of(2026, 9, 26), DailyGameType.MOVIE_BY_INFO, "movie:info"));

        Page<DailyChallenge> page = challengeRepository
                .findByChallengeDateBeforeOrderByChallengeDateDescGameTypeAscIdAsc(
                        LocalDate.of(2026, 9, 27), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(DailyChallenge::getId)
                .containsExactly(olderInfo.getId(), olderMovie.getId());
        assertThat(page.getContent()).doesNotContain(today);
    }

    @Test
    @DisplayName("[findRankingByGameType] Should Aggregate Final Results Exclude In Progress And Rank Ties - When Ranking All Game Types")
    void shouldAggregateFinalResultsExcludeInProgressAndRankTiesWhenRankingAllGameTypes() {
        User first = userRepository.save(buildUser("ranking-first"));
        User second = userRepository.save(buildUser("ranking-second"));
        User third = userRepository.save(buildUser("ranking-third"));
        DailyChallenge firstCompleted = challengeRepository.save(
                buildChallenge(LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "ranking:1"));
        DailyChallenge firstFailed = challengeRepository.save(
                buildChallenge(LocalDate.of(2026, 9, 28), DailyGameType.SERIES_BY_POSTER, "ranking:2"));
        DailyChallenge firstInProgress = challengeRepository.save(
                buildChallenge(LocalDate.of(2026, 9, 29), DailyGameType.PERSON_BY_FACE, "ranking:3"));
        DailyChallenge secondCompleted = challengeRepository.save(
                buildChallenge(LocalDate.of(2026, 9, 30), DailyGameType.MOVIE_BY_INFO, "ranking:4"));
        DailyChallenge thirdCompleted = challengeRepository.saveAndFlush(
                buildChallenge(LocalDate.of(2026, 10, 1), DailyGameType.EPISODE_BY_FRAME, "ranking:5"));

        resultRepository.save(buildResult(first, firstCompleted, 2, 5, DailyGameResultStatus.COMPLETED));
        resultRepository.save(buildResult(first, firstFailed, 10, 0, DailyGameResultStatus.FAILED));
        resultRepository.save(buildResult(first, firstInProgress, 1, 99, DailyGameResultStatus.IN_PROGRESS));
        resultRepository.save(buildResult(second, secondCompleted, 3, 5, DailyGameResultStatus.COMPLETED));
        resultRepository.saveAndFlush(buildResult(third, thirdCompleted, 3, 5, DailyGameResultStatus.COMPLETED));

        Page<UserDailyGameResultRepository.DailyGameRankingProjection> page = resultRepository
                .findRankingByGameType(null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(UserDailyGameResultRepository.DailyGameRankingProjection::getUsername)
                .containsExactly("ranking-second", "ranking-third", "ranking-first");
        assertThat(page.getContent()).extracting(UserDailyGameResultRepository.DailyGameRankingProjection::getRank)
                .containsExactly(1L, 1L, 3L);
        assertThat(page.getContent()).extracting(UserDailyGameResultRepository.DailyGameRankingProjection::getScore)
                .containsExactly(5L, 5L, 5L);
        assertThat(page.getContent()).extracting(UserDailyGameResultRepository.DailyGameRankingProjection::getAttemptsUsed)
                .containsExactly(3L, 3L, 12L);
    }

    @Test
    @DisplayName("[findRankingByGameType] Should Aggregate Only The Requested Game Type - When Specific Ranking Is Queried")
    void shouldAggregateOnlyTheRequestedGameTypeWhenSpecificRankingIsQueried() {
        User user = userRepository.save(buildUser("ranking-specific"));
        DailyChallenge requested = challengeRepository.save(
                buildChallenge(LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_POSTER, "specific:movie"));
        DailyChallenge other = challengeRepository.saveAndFlush(
                buildChallenge(LocalDate.of(2026, 9, 28), DailyGameType.SERIES_BY_POSTER, "specific:series"));
        resultRepository.save(buildResult(user, requested, 2, 4, DailyGameResultStatus.COMPLETED));
        resultRepository.saveAndFlush(buildResult(user, other, 10, 10, DailyGameResultStatus.COMPLETED));

        UserDailyGameResultRepository.DailyGameRankingProjection projection = resultRepository
                .findRankingByGameType(DailyGameType.MOVIE_BY_POSTER.name(), PageRequest.of(0, 10))
                .getContent().getFirst();

        assertThat(projection.getScore()).isEqualTo(4L);
        assertThat(projection.getAttemptsUsed()).isEqualTo(2L);
    }

    @Test
    @DisplayName("[findRankingByGameType] Should Return An Empty Page Without Final Results - When Ranking Is Empty")
    void shouldReturnAnEmptyPageWithoutFinalResultsWhenRankingIsEmpty() {
        assertThat(resultRepository.findRankingByGameType(null, PageRequest.of(0, 10))).isEmpty();
    }

    private DailyChallenge buildChallenge(LocalDate date, DailyGameType type, String answerKey) {
        boolean episode = type.targetKind() == DailyGameTargetKind.EPISODE;
        try {
            return DailyChallenge.builder()
                    .challengeDate(date)
                    .gameType(type)
                    .targetKind(type.targetKind())
                    .targetTmdbId(episode ? null : "550")
                    .seriesTmdbId(episode ? "1396" : null)
                    .seasonNumber(episode ? 1 : null)
                    .episodeNumber(episode ? 1 : null)
                    .answerKey(answerKey)
                    .sourceTmdbId(null)
                    .imagePath("/fight-club.jpg")
                    .answerSnapshot(objectMapper.readTree("{\"answer\":\"Fight Club\"}"))
                    .displaySnapshot(objectMapper.readTree("{\"title\":\"Fight Club\"}"))
                    .createdAt(LocalDateTime.of(2026, 9, 27, 12, 0))
                    .updatedAt(LocalDateTime.of(2026, 9, 27, 12, 0))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void insertChallengeRow(
            String gameType,
            String targetKind,
            String targetTmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber) {
        jdbcTemplate.update("""
                INSERT INTO daily_challenges (
                    id, challenge_date, game_type, target_kind, target_tmdb_id, series_tmdb_id,
                    season_number, episode_number, answer_key, source_tmdb_id, image_path,
                    answer_snapshot, display_snapshot, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """,
                UUID.randomUUID(),
                LocalDate.of(2026, 9, 27),
                gameType,
                targetKind,
                targetTmdbId,
                seriesTmdbId,
                seasonNumber,
                episodeNumber,
                "raw:answer",
                null,
                "/raw-image.jpg",
                "{}",
                "{}",
                LocalDateTime.of(2026, 9, 27, 12, 0),
                LocalDateTime.of(2026, 9, 27, 12, 0));
    }

    private void insertResultRow(
            UUID id,
            UUID userId,
            UUID challengeId,
            int attemptsUsed,
            int score,
            String status) {
        jdbcTemplate.update("""
                INSERT INTO user_daily_game_results (
                    id, user_id, daily_challenge_id, attempts_used, score, status,
                    completed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """,
                id,
                userId,
                challengeId,
                attemptsUsed,
                score,
                status,
                LocalDateTime.of(2026, 9, 27, 12, 0),
                LocalDateTime.of(2026, 9, 27, 12, 0));
    }

    private User buildUser() {
        return buildUser("daily-game-user");
    }

    private User buildUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@email.com")
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .createdAt(LocalDateTime.of(2026, 9, 27, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 12, 0))
                .build();
    }

    private UserDailyGameResult buildResult(
            User user, DailyChallenge challenge, int attempts, int score, DailyGameResultStatus status) {
        return UserDailyGameResult.builder()
                .user(user)
                .dailyChallenge(challenge)
                .attemptsUsed(attempts)
                .score(score)
                .status(status)
                .completedAt(status == DailyGameResultStatus.IN_PROGRESS ? null : NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private UserDailyGameResult buildResult(User user, DailyChallenge challenge, LocalDateTime now) {
        return UserDailyGameResult.builder()
                .user(user)
                .dailyChallenge(challenge)
                .attemptsUsed(0)
                .score(0)
                .status(DailyGameResultStatus.IN_PROGRESS)
                .completedAt(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
