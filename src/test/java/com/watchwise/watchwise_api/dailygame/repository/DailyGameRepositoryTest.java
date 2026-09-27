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

    private User buildUser() {
        return User.builder()
                .username("daily-game-user")
                .email("daily-game-user@email.com")
                .password("hashed_password")
                .profilePicture("https://example.com/photo.png")
                .createdAt(LocalDateTime.of(2026, 9, 27, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 27, 12, 0))
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
