package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.ContentType;
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

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CalendarScheduleCompletenessRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CalendarScheduleCompletenessRepository repository;

    @Autowired
    private CalendarScheduleSnapshotRepository snapshotRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        snapshotRepository.deleteAll();
    }

    @Test
    @DisplayName("[identity] Should Keep Season And Series Completeness Markers Separate")
    void shouldKeepSeasonAndSeriesCompletenessMarkersSeparate() {
        CalendarScheduleCompleteness season = repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SEASON, 2, 13, true));
        CalendarScheduleCompleteness series = repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SERIES, null, 62, true));
        entityManager.clear();

        assertThat(repository.findSeasonIdentity("1396", 2, "BR", "pt-BR"))
                .map(CalendarScheduleCompleteness::getId).contains(season.getId());
        assertThat(repository.findSeriesIdentity("1396", "BR", "pt-BR"))
                .map(CalendarScheduleCompleteness::getId).contains(series.getId());
    }

    @Test
    @DisplayName("[save] Should Preserve An Incomplete Marker")
    void shouldPreserveAnIncompleteMarker() {
        CalendarScheduleCompleteness saved = repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SEASON, 2, 13, false));
        entityManager.clear();

        CalendarScheduleCompleteness reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getComplete()).isFalse();
        assertThat(reloaded.getExpectedEpisodeCount()).isEqualTo(13);
    }

    @Test
    @DisplayName("[save] Should Reject A Series Marker With A Season Number")
    void shouldRejectASeriesMarkerWithASeasonNumber() {
        assertThatThrownBy(() -> repository.saveAndFlush(marker(
                CalendarScheduleCompleteness.GroupType.SERIES, 1, 62, true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_calendar_schedule_completeness_coordinates");
    }

    @Test
    @DisplayName("[findForInterest] Should Return Only Active External Keys And Locale Markers")
    void shouldReturnOnlyActiveExternalKeysAndLocaleMarkers() {
        snapshotRepository.saveAllAndFlush(List.of(
                snapshot(CalendarScheduleSnapshot.EventType.MOVIE, "550", null, "BR", "pt-BR"),
                snapshot(CalendarScheduleSnapshot.EventType.EPISODE, null, "1396", "BR", "pt-BR"),
                snapshot(CalendarScheduleSnapshot.EventType.MOVIE, "680", null, "BR", "pt-BR"),
                snapshot(CalendarScheduleSnapshot.EventType.EPISODE, null, "999", "BR", "pt-BR"),
                snapshot(CalendarScheduleSnapshot.EventType.MOVIE, "155", null, "US", "en-US")));
        repository.saveAllAndFlush(List.of(
                marker(CalendarScheduleCompleteness.GroupType.SEASON, 1, 1, true),
                marker(CalendarScheduleCompleteness.GroupType.SERIES, null, 1, true),
                markerFor("999", CalendarScheduleCompleteness.GroupType.SERIES, null, "BR", "pt-BR")));
        CalendarScheduleSnapshotStore store = new CalendarScheduleSnapshotStore(snapshotRepository, repository,
                mock(NewTransactionExecutor.class));
        CalendarScheduleKey movie = new CalendarScheduleKey(ContentType.MOVIE, "550", "pt-BR", "BR");
        CalendarScheduleKey series = new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR");

        var result = store.findForInterest(new CalendarInterest(List.of("550"), List.of("1396"), List.of(),
                Map.of(movie, Set.of(CalendarSource.WATCHLIST), series, Set.of(CalendarSource.IN_PROGRESS)), "pt-BR", "BR"),
                "BR", "pt-BR");

        assertThat(result.snapshots()).extracting(CalendarScheduleSnapshot::getTmdbId, CalendarScheduleSnapshot::getSeriesTmdbId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("550", null), org.assertj.core.groups.Tuple.tuple(null, "1396"));
        assertThat(result.completeness().completeSeasonKeys())
                .containsExactly(new CalendarAssemblyInput.CompleteSeasonKey("1396", 1));
        assertThat(result.completeness().completeSeriesKeys()).containsExactly(series);
    }

    @Test
    @DisplayName("[reconcileSeries] Should Roll Back All Rows When A Season Reconciliation Fails")
    void shouldRollBackAllRowsWhenASeasonReconciliationFails() {
        // Executed with PostgreSQL/Testcontainers: a real transaction boundary is required for this assertion.
        CalendarScheduleSnapshotStore store = new CalendarScheduleSnapshotStore(snapshotRepository, repository,
                new NewTransactionExecutor());
        CalendarScheduleKey key = new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR");
        CalendarSeriesSchedule schedule = new CalendarSeriesSchedule(key, List.of(
                new CalendarSeriesSchedule.Season(new CalendarSeasonSchedule("1396", 1, "BR", "pt-BR", "Series", null,
                        List.of(new CalendarEpisodeSchedule(1, "One", LocalDate.now(), null, Instant.now(), null))), 1, TmdbLookupOrigin.REMOTE)),
                Map.of(1, 1), 1);

        store.reconcileSeries(schedule, Instant.now());

        assertThat(snapshotRepository.findByEpisodeIdentity("1396", 1, 1, "BR", "pt-BR")).isPresent();
        assertThat(repository.findSeasonIdentity("1396", 1, "BR", "pt-BR")).isPresent();
    }

    private CalendarScheduleSnapshot snapshot(
            CalendarScheduleSnapshot.EventType eventType, String tmdbId, String seriesTmdbId, String region, String language) {
        return CalendarScheduleSnapshot.builder().eventType(eventType).tmdbId(tmdbId).seriesTmdbId(seriesTmdbId)
                .seasonNumber(eventType == CalendarScheduleSnapshot.EventType.EPISODE ? 1 : null)
                .episodeNumber(eventType == CalendarScheduleSnapshot.EventType.EPISODE ? 1 : null)
                .region(region).language(language).releaseDate(LocalDate.of(2026, 9, 12)).title("Title")
                .lastCheckedAt(LocalDateTime.now()).nextCheckAt(LocalDateTime.MAX).presentInLastTmdbSnapshot(true).build();
    }

    private CalendarScheduleCompleteness markerFor(String seriesId, CalendarScheduleCompleteness.GroupType type,
            Integer season, String region, String language) {
        return CalendarScheduleCompleteness.builder().groupType(type).seriesTmdbId(seriesId).seasonNumber(season)
                .region(region).language(language).expectedEpisodeCount(1).complete(true).lastCheckedAt(LocalDateTime.now()).build();
    }

    private CalendarScheduleCompleteness marker(
            CalendarScheduleCompleteness.GroupType groupType,
            Integer seasonNumber,
            int expectedEpisodeCount,
            boolean complete) {
        return CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId("1396")
                .seasonNumber(seasonNumber)
                .region("BR")
                .language("pt-BR")
                .expectedEpisodeCount(expectedEpisodeCount)
                .complete(complete)
                .lastCheckedAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .build();
    }
}
