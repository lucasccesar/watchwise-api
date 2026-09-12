package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CalendarScheduleSnapshotRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CalendarScheduleSnapshotRepository snapshotRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        snapshotRepository.deleteAll();
    }

    @Test
    @DisplayName("[save] Should Persist And Reload A Movie Snapshot")
    void shouldPersistAndReloadAMovieSnapshot() {
        CalendarScheduleSnapshot saved = snapshotRepository.saveAndFlush(buildMovie(
                "550", LocalDate.of(2026, 9, 12), "Fight Club", "/fight-club.jpg"));
        entityManager.clear();

        CalendarScheduleSnapshot reloaded = snapshotRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEventType()).isEqualTo(CalendarScheduleSnapshot.EventType.MOVIE);
        assertThat(reloaded.getTmdbId()).isEqualTo("550");
        assertThat(reloaded.getRegion()).isEqualTo("BR");
        assertThat(reloaded.getLanguage()).isEqualTo("pt-BR");
        assertThat(reloaded.getReleaseDate()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(reloaded.getTitle()).isEqualTo("Fight Club");
        assertThat(reloaded.getPosterPath()).isEqualTo("/fight-club.jpg");
        assertThat(reloaded.getPresentInLastTmdbSnapshot()).isTrue();
        assertThat(reloaded.getSeriesTmdbId()).isNull();
        assertThat(reloaded.getSeasonNumber()).isNull();
        assertThat(reloaded.getEpisodeNumber()).isNull();
    }

    @Test
    @DisplayName("[save] Should Persist And Reload An Episode Snapshot")
    void shouldPersistAndReloadAnEpisodeSnapshot() {
        CalendarScheduleSnapshot saved = snapshotRepository.saveAndFlush(buildEpisode(
                "1396", 2, 3, LocalDate.of(2026, 9, 13), "Four Days Out"));
        entityManager.clear();

        CalendarScheduleSnapshot reloaded = snapshotRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEventType()).isEqualTo(CalendarScheduleSnapshot.EventType.EPISODE);
        assertThat(reloaded.getSeriesTmdbId()).isEqualTo("1396");
        assertThat(reloaded.getSeasonNumber()).isEqualTo(2);
        assertThat(reloaded.getEpisodeNumber()).isEqualTo(3);
        assertThat(reloaded.getTitle()).isEqualTo("Four Days Out");
        assertThat(reloaded.getSeriesTitle()).isEqualTo("Breaking Bad");
        assertThat(reloaded.getStillPath()).isEqualTo("/four-days-out.jpg");
        assertThat(reloaded.getTmdbId()).isNull();
    }

    @Test
    @DisplayName("[save] Should Allow Nullable Display Paths")
    void shouldAllowNullableDisplayPaths() {
        CalendarScheduleSnapshot saved = snapshotRepository.saveAndFlush(buildEpisode(
                "1396", 2, 3, LocalDate.of(2026, 9, 13), "Four Days Out").toBuilder()
                .stillPath(null)
                .build());
        entityManager.clear();

        CalendarScheduleSnapshot reloaded = snapshotRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getPosterPath()).isNull();
        assertThat(reloaded.getStillPath()).isNull();
    }

    @Test
    @DisplayName("[identity] Should Keep Movie And Episode Coordinates Separate")
    void shouldKeepMovieAndEpisodeCoordinatesSeparate() {
        CalendarScheduleSnapshot movie = snapshotRepository.save(buildMovie(
                "1396", LocalDate.of(2026, 9, 12), "Movie", null));
        CalendarScheduleSnapshot episode = snapshotRepository.saveAndFlush(buildEpisode(
                "1396", 1, 1, LocalDate.of(2026, 9, 12), "Episode"));

        assertThat(movie.getId()).isNotEqualTo(episode.getId());
        assertThat(snapshotRepository.findByMovieIdentity("1396", "BR", "pt-BR"))
                .map(CalendarScheduleSnapshot::getId)
                .contains(movie.getId());
        assertThat(snapshotRepository.findByEpisodeIdentity("1396", 1, 1, "BR", "pt-BR"))
                .map(CalendarScheduleSnapshot::getId)
                .contains(episode.getId());
    }

    @Test
    @DisplayName("[save] Should Reject Season Zero")
    void shouldRejectSeasonZero() {
        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(buildEpisode(
                "1396", 0, 1, LocalDate.of(2026, 9, 13), "Special")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_calendar_schedule_snapshots_positive_coordinates");
    }

    @Test
    @DisplayName("[findDue] Should Return Snapshots Due At The Requested Time")
    void shouldReturnSnapshotsDueAtTheRequestedTime() {
        LocalDateTime dueAt = LocalDateTime.of(2026, 9, 12, 12, 0);
        CalendarScheduleSnapshot due = snapshotRepository.save(buildMovie(
                "550", LocalDate.of(2026, 9, 12), "Due", null).toBuilder()
                .nextCheckAt(dueAt.minusMinutes(1))
                .build());
        snapshotRepository.saveAndFlush(buildMovie(
                "680", LocalDate.of(2026, 9, 12), "Not Due", null).toBuilder()
                .nextCheckAt(dueAt.plusMinutes(1))
                .build());

        List<CalendarScheduleSnapshot> result = snapshotRepository.findDueAtOrBefore(dueAt);

        assertThat(result).extracting(CalendarScheduleSnapshot::getId).containsExactly(due.getId());
    }

    @Test
    @DisplayName("[findByReleaseMonth] Should Filter By Month Region And Language")
    void shouldFilterByMonthRegionAndLanguage() {
        CalendarScheduleSnapshot september = snapshotRepository.save(buildMovie(
                "550", LocalDate.of(2026, 9, 12), "September", null));
        snapshotRepository.save(buildMovie(
                "680", LocalDate.of(2026, 10, 1), "October", null));
        snapshotRepository.saveAndFlush(buildMovie(
                "155", LocalDate.of(2026, 9, 15), "Other Region", null).toBuilder()
                .region("US")
                .build());

        List<CalendarScheduleSnapshot> result = snapshotRepository.findByReleaseMonth(
                YearMonth.of(2026, 9), "BR", "pt-BR");

        assertThat(result).extracting(CalendarScheduleSnapshot::getId).containsExactly(september.getId());
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Movie Identity")
    void shouldRejectDuplicateMovieIdentity() {
        snapshotRepository.saveAndFlush(buildMovie("550", LocalDate.of(2026, 9, 12), "First", null));

        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(
                buildMovie("550", LocalDate.of(2026, 9, 13), "Second", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_calendar_schedule_snapshots_movie_identity");
    }

    @Test
    @DisplayName("[save] Should Reject Duplicate Episode Identity")
    void shouldRejectDuplicateEpisodeIdentity() {
        snapshotRepository.saveAndFlush(buildEpisode("1396", 2, 3,
                LocalDate.of(2026, 9, 12), "First"));

        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(buildEpisode("1396", 2, 3,
                LocalDate.of(2026, 9, 13), "Second")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_calendar_schedule_snapshots_episode_identity");
    }

    @Test
    @DisplayName("[save] Should Reject Movie Coordinates On An Episode")
    void shouldRejectMovieCoordinatesOnAnEpisode() {
        CalendarScheduleSnapshot invalid = buildEpisode("1396", 2, 3,
                LocalDate.of(2026, 9, 13), "Episode").toBuilder()
                .tmdbId("550")
                .build();

        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_calendar_schedule_snapshots_coordinates");
    }

    @Test
    @DisplayName("[save] Should Reject Episode Coordinates On A Movie")
    void shouldRejectEpisodeCoordinatesOnAMovie() {
        CalendarScheduleSnapshot invalid = buildMovie(
                "550", LocalDate.of(2026, 9, 12), "Movie", null).toBuilder()
                .seriesTmdbId("1396")
                .seasonNumber(2)
                .episodeNumber(3)
                .build();

        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_calendar_schedule_snapshots_coordinates");
    }

    private CalendarScheduleSnapshot buildMovie(String tmdbId, LocalDate releaseDate, String title,
                                                 String posterPath) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                .tmdbId(tmdbId)
                .region("BR")
                .language("pt-BR")
                .releaseDate(releaseDate)
                .title(title)
                .posterPath(posterPath)
                .lastCheckedAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .nextCheckAt(LocalDateTime.of(2026, 9, 13, 10, 0))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private CalendarScheduleSnapshot buildEpisode(String seriesTmdbId, Integer seasonNumber,
                                                   Integer episodeNumber, LocalDate releaseDate, String title) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .region("BR")
                .language("pt-BR")
                .releaseDate(releaseDate)
                .title(title)
                .seriesTitle("Breaking Bad")
                .stillPath("/four-days-out.jpg")
                .lastCheckedAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .nextCheckAt(LocalDateTime.of(2026, 9, 13, 10, 0))
                .presentInLastTmdbSnapshot(true)
                .build();
    }
}
