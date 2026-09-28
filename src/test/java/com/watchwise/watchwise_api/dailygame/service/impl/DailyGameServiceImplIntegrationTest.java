package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.entity.UserDailyGameResult;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeHintRepository;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.repository.UserDailyGameResultRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateIdentity;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateValidator;
import com.watchwise.watchwise_api.dailygame.service.DailyGameService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class DailyGameServiceImplIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private DailyGameService dailyGameService;

    @MockitoBean
    private DailyChallengeRepository challengeRepository;

    @Autowired
    private DailyChallengeHintRepository hintRepository;

    @Autowired
    private UserDailyGameResultRepository resultRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Clock clock;

    @MockitoBean
    private DailyGameCandidateValidator candidateValidator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        removeInsertDelay();
        resultRepository.deleteAll();
        hintRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM daily_challenges");
        userRepository.deleteAll();
        installInsertDelay();
    }

    @AfterEach
    void tearDown() {
        removeInsertDelay();
    }

    @Test
    @DisplayName("[submitAttempt] Should Create One Result And Consume One Attempt - When Two First Submissions Race")
    void shouldCreateOneResultAndConsumeOneAttemptWhenTwoFirstSubmissionsRace() throws Exception {
        User user = userRepository.saveAndFlush(user());
        DailyChallenge challenge = persistChallenge();

        when(challengeRepository.findByChallengeDateAndGameType(LocalDate.now(clock), DailyGameType.MOVIE_BY_POSTER))
                .thenReturn(Optional.of(challenge));
        when(candidateValidator.validate(DailyGameType.MOVIE_BY_POSTER,
                new DailyGameAttemptRequest("550", null, null, null, null)))
                .thenReturn(new DailyGameCandidateIdentity(DailyGameTargetKind.MOVIE, "550", null, null, null, null));

        Callable<SubmissionOutcome> submission = () -> {
            try {
                dailyGameService.submitAttempt(user.getId(), DailyGameType.MOVIE_BY_POSTER,
                        new DailyGameAttemptRequest("550", null, null, null, null));
                return new SubmissionOutcome(true, null);
            } catch (ConflictException exception) {
                return new SubmissionOutcome(false, exception);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SubmissionOutcome> first = executor.submit(submission);
            awaitInsertAdvisoryLock();
            Future<SubmissionOutcome> second = executor.submit(submission);
            awaitWaitingInsertAdvisoryLock();
            List<SubmissionOutcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).extracting(SubmissionOutcome::succeeded).containsExactlyInAnyOrder(true, false);
            assertThat(outcomes).extracting(SubmissionOutcome::failure)
                    .filteredOn(failure -> failure != null)
                    .allSatisfy(failure -> assertThat(failure).isInstanceOf(ConflictException.class));
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Daily game race executor did not terminate");
            }
        }

        List<UserDailyGameResult> results = resultRepository.findAll();
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getDailyChallenge().getId()).isEqualTo(challenge.getId());
        assertThat(results.getFirst().getAttemptsUsed()).isEqualTo(1);
    }

    private User user() {
        LocalDateTime now = LocalDateTime.now(clock);
        return User.builder()
                .username("daily-game-race-user")
                .email("daily-game-race@example.com")
                .password("encoded-password")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DailyChallenge challenge() {
        LocalDateTime now = LocalDateTime.now(clock);
        return DailyChallenge.builder()
                .challengeDate(LocalDate.now(clock))
                .gameType(DailyGameType.MOVIE_BY_POSTER)
                .targetKind(DailyGameTargetKind.MOVIE)
                .targetTmdbId("550")
                .answerKey("MOVIE:550")
                .imagePath("https://image.tmdb.org/t/p/w500/fight-club.jpg")
                .answerSnapshot(objectMapper.createObjectNode().put("title", "Fight Club"))
                .displaySnapshot(objectMapper.createObjectNode())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DailyChallenge persistChallenge() {
        DailyChallenge challenge = challenge().toBuilder().id(UUID.randomUUID()).build();
        jdbcTemplate.update("""
                INSERT INTO daily_challenges (
                    id, challenge_date, game_type, target_kind, target_tmdb_id, series_tmdb_id,
                    season_number, episode_number, answer_key, source_tmdb_id, image_path,
                    answer_snapshot, display_snapshot, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """,
                challenge.getId(),
                challenge.getChallengeDate(),
                challenge.getGameType().name(),
                challenge.getTargetKind().name(),
                challenge.getTargetTmdbId(),
                challenge.getSeriesTmdbId(),
                challenge.getSeasonNumber(),
                challenge.getEpisodeNumber(),
                challenge.getAnswerKey(),
                challenge.getSourceTmdbId(),
                challenge.getImagePath(),
                "{}",
                "{}",
                challenge.getCreatedAt(),
                challenge.getUpdatedAt());
        return challenge;
    }

    private void installInsertDelay() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION daily_game_result_insert_delay()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock(hashtext('daily-game-test-insert'));
                    PERFORM pg_sleep(2);
                    RETURN NEW;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER daily_game_result_insert_delay_trigger
                BEFORE INSERT ON user_daily_game_results
                FOR EACH ROW
                EXECUTE FUNCTION daily_game_result_insert_delay()
                """);
    }

    private void removeInsertDelay() {
        try {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS daily_game_result_insert_delay_trigger ON user_daily_game_results");
        } finally {
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS daily_game_result_insert_delay()");
        }
    }

    private void awaitInsertAdvisoryLock() throws InterruptedException {
        awaitAdvisoryLock(true, "granted");
    }

    private void awaitWaitingInsertAdvisoryLock() throws InterruptedException {
        awaitAdvisoryLock(false, "waiting");
    }

    private void awaitAdvisoryLock(boolean granted, String state) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean locked = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks
                        WHERE locktype = 'advisory'
                          AND granted = ?
                          AND classid = 0
                          AND objsubid = 1
                          AND (objid::bigint & 4294967295) = (hashtext('daily-game-test-insert')::bigint & 4294967295)
                    )
                    """, Boolean.class, granted);
            if (Boolean.TRUE.equals(locked)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Timed out waiting for the " + state + " daily game insert advisory lock");
    }

    private record SubmissionOutcome(boolean succeeded, Throwable failure) {
    }
}
