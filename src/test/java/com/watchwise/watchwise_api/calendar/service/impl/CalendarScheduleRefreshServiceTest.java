package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleSynchronizer;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarScheduleRefreshServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 9, 12, 10, 0);

    @Mock
    private WatchlistEntryRepository watchlistEntryRepository;
    @Mock
    private CalendarScheduleSnapshotStore snapshotStore;
    @Mock
    private CalendarScheduleProvider scheduleProvider;
    @Mock
    private CalendarScheduleSynchronizer scheduleSynchronizer;

    private ExecutorService executor;
    private CalendarScheduleRefreshService refreshService;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        refreshService = new CalendarScheduleRefreshService(
                watchlistEntryRepository,
                snapshotStore,
                scheduleProvider,
                scheduleSynchronizer,
                executor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Deduplicate Due Seasons By Identity And Locale")
    void shouldDeduplicateDueSeasonsByIdentityAndLocale() {
        CalendarScheduleKey moviePtBr = key(ContentType.MOVIE, "550", "pt-BR", "BR");
        CalendarScheduleKey movieEnUs = key(ContentType.MOVIE, "550", "en-US", "US");
        CalendarScheduleKey seriesPtBr = key(ContentType.SERIES, "1396", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys())
                .thenReturn(List.of(moviePtBr, movieEnUs, seriesPtBr, seriesPtBr));
        when(snapshotStore.findDue(Set.of(moviePtBr, movieEnUs, seriesPtBr), NOW_UTC))
                .thenReturn(List.of(
                        movieSnapshot("550", "BR", "pt-BR"),
                        movieSnapshot("550", "BR", "pt-BR"),
                        movieSnapshot("550", "US", "en-US"),
                        episodeSnapshot("1396", 1, 1, "BR", "pt-BR"),
                        episodeSnapshot("1396", 1, 2, "BR", "pt-BR"),
                        episodeSnapshot("1396", 2, 1, "BR", "pt-BR")));
        when(scheduleProvider.loadMovie(any(), any(), any())).thenAnswer(invocation ->
                remoteMovie(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(scheduleProvider.loadSeason("1396", 1, "BR", "pt-BR"))
                .thenReturn(remoteSeason("1396", 1, "BR", "pt-BR"));
        when(scheduleProvider.loadSeason("1396", 2, "BR", "pt-BR"))
                .thenReturn(remoteSeason("1396", 2, "BR", "pt-BR"));

        refreshService.refreshDueSchedules();

        verify(scheduleProvider).loadMovie("550", "BR", "pt-BR");
        verify(scheduleProvider).loadMovie("550", "US", "en-US");
        verify(scheduleProvider).loadSeason("1396", 1, "BR", "pt-BR");
        verify(scheduleProvider).loadSeason("1396", 2, "BR", "pt-BR");
        verify(scheduleProvider, never()).loadSeries(any(), any(), any());
        verify(scheduleSynchronizer, org.mockito.Mockito.times(4)).synchronize(any(), eq(NOW));
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Discover A Series When Its Discovery Marker Is Due")
    void shouldDiscoverASeriesWhenItsDiscoveryMarkerIsDue() {
        CalendarScheduleKey activeSeries = key(ContentType.SERIES, "1396", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys()).thenReturn(List.of(activeSeries));
        when(snapshotStore.findDue(Set.of(activeSeries), NOW_UTC)).thenReturn(List.of());
        when(snapshotStore.findSeriesDueForDiscovery(Set.of(activeSeries), NOW)).thenReturn(Set.of(activeSeries));
        when(scheduleProvider.loadSeries("1396", "BR", "pt-BR"))
                .thenReturn(remoteSeries("1396", "BR", "pt-BR"));

        refreshService.refreshDueSchedules();

        verify(scheduleProvider).loadSeries("1396", "BR", "pt-BR");
        verify(scheduleSynchronizer).synchronizeSeries(any(CalendarSeriesSchedule.class), eq(NOW));
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Exclude Orphan Snapshots")
    void shouldExcludeOrphanSnapshots() {
        CalendarScheduleKey activeMovie = key(ContentType.MOVIE, "550", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys()).thenReturn(List.of(activeMovie));
        when(snapshotStore.findDue(Set.of(activeMovie), NOW_UTC)).thenReturn(List.of(
                movieSnapshot("550", "BR", "pt-BR"), movieSnapshot("680", "BR", "pt-BR")));
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR"))
                .thenReturn(remoteMovie("550", "BR", "pt-BR"));

        refreshService.refreshDueSchedules();

        verify(scheduleProvider).loadMovie("550", "BR", "pt-BR");
        verify(scheduleProvider, never()).loadMovie("680", "BR", "pt-BR");
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Pass The Current Instant To Due Selection")
    void shouldPassTheCurrentInstantToDueSelection() {
        CalendarScheduleKey activeMovie = key(ContentType.MOVIE, "550", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys()).thenReturn(List.of(activeMovie));
        when(snapshotStore.findDue(Set.of(activeMovie), NOW_UTC)).thenReturn(List.of());

        refreshService.refreshDueSchedules();

        verify(snapshotStore).findDue(Set.of(activeMovie), NOW_UTC);
        verifyNoInteractions(scheduleProvider, scheduleSynchronizer);
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Preserve Snapshots For Not Found Or Unavailable Results")
    void shouldPreserveSnapshotsForNotFoundOrUnavailableResults() {
        CalendarScheduleKey notFoundMovie = key(ContentType.MOVIE, "550", "pt-BR", "BR");
        CalendarScheduleKey unavailableMovie = key(ContentType.MOVIE, "680", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys())
                .thenReturn(List.of(notFoundMovie, unavailableMovie));
        when(snapshotStore.findDue(Set.of(notFoundMovie, unavailableMovie), NOW_UTC)).thenReturn(List.of(
                movieSnapshot("550", "BR", "pt-BR"), movieSnapshot("680", "BR", "pt-BR")));
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR")).thenReturn(new CalendarScheduleLookup.NotFound());
        when(scheduleProvider.loadMovie("680", "BR", "pt-BR")).thenReturn(new CalendarScheduleLookup.Unavailable());

        refreshService.refreshDueSchedules();

        verifyNoInteractions(scheduleSynchronizer);
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Ignore Cache-Origin Results")
    void shouldIgnoreCacheOriginResults() {
        CalendarScheduleKey activeMovie = key(ContentType.MOVIE, "550", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys()).thenReturn(List.of(activeMovie));
        when(snapshotStore.findDue(Set.of(activeMovie), NOW_UTC)).thenReturn(List.of(
                movieSnapshot("550", "BR", "pt-BR")));
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR"))
                .thenReturn(remoteMovieWithOrigin("550", "BR", "pt-BR", TmdbLookupOrigin.CACHE));

        refreshService.refreshDueSchedules();

        verifyNoInteractions(scheduleSynchronizer);
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Continue With Other Keys After One Key Fails")
    void shouldContinueWithOtherKeysAfterOneKeyFails() {
        CalendarScheduleKey failedMovie = key(ContentType.MOVIE, "550", "pt-BR", "BR");
        CalendarScheduleKey successfulMovie = key(ContentType.MOVIE, "680", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys())
                .thenReturn(List.of(failedMovie, successfulMovie));
        when(snapshotStore.findDue(Set.of(failedMovie, successfulMovie), NOW_UTC)).thenReturn(List.of(
                movieSnapshot("550", "BR", "pt-BR"), movieSnapshot("680", "BR", "pt-BR")));
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR"))
                .thenThrow(new IllegalStateException("TMDB timeout"));
        when(scheduleProvider.loadMovie("680", "BR", "pt-BR"))
                .thenReturn(remoteMovie("680", "BR", "pt-BR"));

        refreshService.refreshDueSchedules();

        verify(scheduleSynchronizer).synchronize(any(), eq(NOW));
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Never Persist A Cache-Origin Schedule")
    void shouldNeverPersistACacheOriginSchedule() {
        CalendarScheduleKey activeSeries = key(ContentType.SERIES, "1396", "pt-BR", "BR");
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys()).thenReturn(List.of(activeSeries));
        when(snapshotStore.findDue(Set.of(activeSeries), NOW_UTC)).thenReturn(List.of(
                episodeSnapshot("1396", 2, 1, "BR", "pt-BR")));
        when(scheduleProvider.loadSeason("1396", 2, "BR", "pt-BR"))
                .thenReturn(remoteSeasonWithOrigin("1396", 2, "BR", "pt-BR", TmdbLookupOrigin.CACHE));

        refreshService.refreshDueSchedules();

        verifyNoInteractions(scheduleSynchronizer);
    }

    @Test
    @DisplayName("[refreshDueSchedules] Should Use The Shared Bounded Executor")
    void shouldUseTheSharedBoundedExecutor() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        doAnswer(invocation -> {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            Thread.sleep(25);
            active.decrementAndGet();
            return remoteMovie(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(scheduleProvider).loadMovie(any(), any(), any());

        List<CalendarScheduleKey> activeKeys = List.of(
                key(ContentType.MOVIE, "550", "pt-BR", "BR"),
                key(ContentType.MOVIE, "680", "pt-BR", "BR"),
                key(ContentType.MOVIE, "155", "pt-BR", "BR"),
                key(ContentType.MOVIE, "272", "pt-BR", "BR"));
        when(watchlistEntryRepository.findActiveCalendarScheduleKeys()).thenReturn(activeKeys);
        when(snapshotStore.findDue(Set.copyOf(activeKeys), NOW_UTC)).thenReturn(activeKeys.stream()
                .map(key -> movieSnapshot(key.tmdbId(), key.preferredRegion(), key.preferredLanguage())).toList());

        refreshService.refreshDueSchedules();

        assertThat(maximum).hasValue(2);
    }

    private static CalendarScheduleKey key(ContentType type, String tmdbId, String language, String region) {
        return new CalendarScheduleKey(type, tmdbId, language, region);
    }

    private static CalendarScheduleSnapshot movieSnapshot(String tmdbId, String region, String language) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                .tmdbId(tmdbId)
                .region(region)
                .language(language)
                .releaseDate(LocalDate.of(2026, 9, 20))
                .title("Movie " + tmdbId)
                .lastCheckedAt(NOW_UTC.minusDays(1))
                .nextCheckAt(NOW_UTC)
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private static CalendarScheduleSnapshot episodeSnapshot(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, String region, String language) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .region(region)
                .language(language)
                .releaseDate(LocalDate.of(2026, 9, 20))
                .title("Episode " + episodeNumber)
                .seriesTitle("Series " + seriesTmdbId)
                .lastCheckedAt(NOW_UTC.minusDays(1))
                .nextCheckAt(NOW_UTC)
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private static CalendarScheduleLookup.Found remoteMovie(String tmdbId, String region, String language) {
        return remoteMovieWithOrigin(tmdbId, region, language, TmdbLookupOrigin.REMOTE);
    }

    private static CalendarScheduleLookup.Found remoteMovieWithOrigin(
            String tmdbId, String region, String language, TmdbLookupOrigin origin) {
        CalendarScheduleKey key = key(ContentType.MOVIE, tmdbId, language, region);
        return new CalendarScheduleLookup.Found(new CalendarScheduleBatch(
                key,
                origin,
                NOW,
                new CalendarMovieSchedule(tmdbId, region, language, LocalDate.of(2026, 9, 20),
                        "Movie " + tmdbId, null, null, null),
                null));
    }

    private static CalendarScheduleLookup.Found remoteSeason(
            String seriesTmdbId, Integer seasonNumber, String region, String language) {
        return remoteSeasonWithOrigin(seriesTmdbId, seasonNumber, region, language, TmdbLookupOrigin.REMOTE);
    }

    private static CalendarScheduleLookup.Found remoteSeasonWithOrigin(
            String seriesTmdbId, Integer seasonNumber, String region, String language, TmdbLookupOrigin origin) {
        CalendarScheduleKey key = key(ContentType.SERIES, seriesTmdbId, language, region);
        CalendarSeasonSchedule season = new CalendarSeasonSchedule(
                seriesTmdbId, seasonNumber, region, language, "Series " + seriesTmdbId, null,
                List.of(new CalendarEpisodeSchedule(1, "Episode 1", LocalDate.of(2026, 9, 20), null, null, null)));
        return new CalendarScheduleLookup.Found(new CalendarScheduleBatch(
                key, origin, NOW, null, season));
    }

    private static CalendarScheduleLookup.FoundSeries remoteSeries(String seriesTmdbId, String region, String language) {
        return remoteSeriesWithOrigin(seriesTmdbId, region, language, TmdbLookupOrigin.REMOTE);
    }

    private static CalendarScheduleLookup.FoundSeries remoteSeriesWithOrigin(
            String seriesTmdbId, String region, String language, TmdbLookupOrigin origin) {
        CalendarScheduleKey key = key(ContentType.SERIES, seriesTmdbId, language, region);
        CalendarSeasonSchedule season = new CalendarSeasonSchedule(
                seriesTmdbId, 1, region, language, "Series " + seriesTmdbId, null,
                List.of(new CalendarEpisodeSchedule(1, "Episode 1", LocalDate.of(2026, 9, 20), null, null, null)));
        return new CalendarScheduleLookup.FoundSeries(new CalendarSeriesSchedule(
                key,
                List.of(new CalendarSeriesSchedule.Season(season, 1, origin)),
                java.util.Map.of(1, 1),
                1));
    }
}
