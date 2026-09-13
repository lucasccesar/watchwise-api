package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.dto.CalendarEventType;
import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarInterestReader;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleReadModel;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleSynchronizer;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import com.watchwise.watchwise_api.calendar.service.CalendarWatchedContentReader;
import com.watchwise.watchwise_api.calendar.service.WatchedCalendarKey;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);

    @Mock private CalendarInterestReader interestReader;
    @Mock private CalendarScheduleSnapshotStore snapshotStore;
    @Mock private CalendarScheduleProvider scheduleProvider;
    @Mock private CalendarScheduleSynchronizer synchronizer;
    @Mock private CalendarWatchedContentReader watchedReader;

    @Test
    void returnsEmptyMonthWithoutScheduleReadsWhenInterestIsEmpty() {
        when(interestReader.read(USER_ID)).thenReturn(interest(Map.of()));

        CalendarResponseDTO response = service().getMonth(USER_ID, YearMonth.of(2026, 9));

        assertThat(response.events()).isEmpty();
        assertThat(response.region()).isEqualTo("BR");
        verifyNoInteractions(snapshotStore, scheduleProvider, synchronizer, watchedReader);
    }

    @Test
    void composesMovieAndSeriesSourcesWithOneWatchedBatchAndLocale() {
        CalendarScheduleKey movieKey = key(ContentType.MOVIE, "550");
        CalendarScheduleKey seriesKey = key(ContentType.SERIES, "1396");
        CalendarInterest interest = interest(Map.of(
                movieKey, Set.of(CalendarSource.WATCHLIST),
                seriesKey, Set.of(CalendarSource.WATCHLIST, CalendarSource.IN_PROGRESS)));
        CalendarScheduleReadModel read = new CalendarScheduleReadModel(
                List.of(movie("550", LocalDate.of(2026, 9, 15)), episode("1396", 1, 1, LocalDate.of(2026, 9, 16))),
                new CalendarAssemblyInput.Completeness(Set.of(new CalendarAssemblyInput.CompleteSeasonKey("1396", 1)), Set.of()));
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(read);
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        CalendarResponseDTO response = service().getMonth(USER_ID, YearMonth.of(2026, 9));

        assertThat(response.events()).extracting(event -> event.eventType())
                .containsExactly(CalendarEventType.MOVIE, CalendarEventType.SEASON);
        ArgumentCaptor<Set<WatchedCalendarKey>> requested = ArgumentCaptor.forClass(Set.class);
        verify(watchedReader).readWatchedKeys(org.mockito.ArgumentMatchers.eq(USER_ID), requested.capture());
        assertThat(requested.getValue()).containsExactlyInAnyOrder(WatchedCalendarKey.movie("550"),
                WatchedCalendarKey.episode("1396", 1, 1));
        verify(snapshotStore).findForInterest(interest, "BR", "pt-BR");
    }

    @Test
    void refreshesAMissingMovieOnlyOnceAndRereadsRemoteFacts() {
        CalendarScheduleKey key = key(ContentType.MOVIE, "550");
        CalendarInterest interest = interest(Map.of(key, Set.of(CalendarSource.WATCHLIST)));
        CalendarScheduleReadModel empty = new CalendarScheduleReadModel(List.of(), CalendarAssemblyInput.Completeness.empty());
        CalendarScheduleReadModel refreshed = new CalendarScheduleReadModel(
                List.of(movie("550", LocalDate.of(2026, 9, 15))), CalendarAssemblyInput.Completeness.empty());
        CalendarScheduleBatch batch = CalendarScheduleBatch.movie(key, TmdbLookupOrigin.REMOTE,
                new CalendarMovieSchedule("550", "BR", "pt-BR", LocalDate.of(2026, 9, 15), "Fight Club", null, null, null));
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(empty, refreshed);
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR")).thenReturn(new CalendarScheduleLookup.Found(batch));
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        service().getMonth(USER_ID, YearMonth.of(2026, 9));

        verify(scheduleProvider).loadMovie("550", "BR", "pt-BR");
        verify(synchronizer).synchronize(batch, CLOCK.instant());
        verify(snapshotStore, org.mockito.Mockito.times(2)).findForInterest(interest, "BR", "pt-BR");
    }

    @Test
    void usesACachedFoundMovieInTheResponseWhenNoRegionalSnapshotExists() {
        CalendarScheduleKey key = key(ContentType.MOVIE, "550");
        CalendarInterest interest = interest(Map.of(key, Set.of(CalendarSource.WATCHLIST)));
        CalendarScheduleReadModel empty = new CalendarScheduleReadModel(List.of(), CalendarAssemblyInput.Completeness.empty());
        CalendarScheduleBatch cached = CalendarScheduleBatch.movie(key, TmdbLookupOrigin.CACHE,
                new CalendarMovieSchedule("550", "BR", "pt-BR", LocalDate.of(2026, 9, 15), "Fight Club", null, null, null));
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(empty);
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR")).thenReturn(new CalendarScheduleLookup.Found(cached));
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        CalendarResponseDTO response = service().getMonth(USER_ID, YearMonth.of(2026, 9));

        assertThat(response.events()).extracting(event -> event.eventType()).containsExactly(CalendarEventType.MOVIE);
        verify(synchronizer, never()).synchronize(any(), any());
        verify(snapshotStore).findForInterest(interest, "BR", "pt-BR");
    }

    @Test
    void keepsCachedSeriesSeasonsInTheResponseWhenAnotherSeasonWasSynchronizedRemotely() {
        CalendarScheduleKey key = key(ContentType.SERIES, "1396");
        CalendarInterest interest = interest(Map.of(key, Set.of(CalendarSource.WATCHLIST)));
        CalendarScheduleReadModel empty = new CalendarScheduleReadModel(List.of(), CalendarAssemblyInput.Completeness.empty());
        CalendarScheduleReadModel refreshed = new CalendarScheduleReadModel(
                List.of(episode("1396", 2, 1, LocalDate.of(2026, 9, 16))), CalendarAssemblyInput.Completeness.empty());
        CalendarSeasonSchedule cachedSeason = new CalendarSeasonSchedule(
                "1396", 1, "BR", "pt-BR", "Series 1396", null,
                List.of(new CalendarEpisodeSchedule(1, "Cached episode", LocalDate.of(2026, 9, 15), null, null, null)));
        CalendarSeasonSchedule remoteSeason = new CalendarSeasonSchedule(
                "1396", 2, "BR", "pt-BR", "Series 1396", null,
                List.of(new CalendarEpisodeSchedule(1, "Remote episode", LocalDate.of(2026, 9, 16), null, null, null)));
        CalendarSeriesSchedule schedule = new CalendarSeriesSchedule(
                key,
                List.of(
                        new CalendarSeriesSchedule.Season(cachedSeason, 1, TmdbLookupOrigin.CACHE),
                        new CalendarSeriesSchedule.Season(remoteSeason, 1, TmdbLookupOrigin.REMOTE)),
                Map.of(1, 1, 2, 1), 2);
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(empty, refreshed);
        when(scheduleProvider.loadSeries("1396", "BR", "pt-BR"))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        assertThat(service().getMonth(USER_ID, YearMonth.of(2026, 9)).events())
                .extracting(event -> event.eventType())
                .containsExactly(CalendarEventType.EPISODE, CalendarEventType.EPISODE);
        verify(synchronizer).synchronizeSeries(schedule, CLOCK.instant());
    }

    @Test
    void refreshesDueSeriesThroughLoadSeriesAndNeverLoadSeason() {
        CalendarScheduleKey key = key(ContentType.SERIES, "1396");
        CalendarInterest interest = interest(Map.of(key, Set.of(CalendarSource.WATCHLIST)));
        CalendarScheduleReadModel read = new CalendarScheduleReadModel(
                List.of(episode("1396", 1, 1, LocalDate.of(2026, 9, 10)).toBuilder()
                        .nextCheckAt(LocalDateTime.of(2026, 9, 12, 10, 0)).build()), CalendarAssemblyInput.Completeness.empty());
        CalendarSeriesSchedule schedule = new CalendarSeriesSchedule(key, List.of(), Map.of(), 0);
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(read, read);
        when(scheduleProvider.loadSeries("1396", "BR", "pt-BR"))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        service().getMonth(USER_ID, YearMonth.of(2026, 9));

        verify(scheduleProvider).loadSeries("1396", "BR", "pt-BR");
        verify(scheduleProvider, never()).loadSeason(any(), any(), any(), any());
    }

    @Test
    void omitsNotFoundButFailsForUnavailableWithoutSnapshot() {
        CalendarScheduleKey key = key(ContentType.MOVIE, "550");
        CalendarInterest interest = interest(Map.of(key, Set.of(CalendarSource.WATCHLIST)));
        CalendarScheduleReadModel empty = new CalendarScheduleReadModel(List.of(), CalendarAssemblyInput.Completeness.empty());
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(empty);
        when(scheduleProvider.loadMovie("550", "BR", "pt-BR")).thenReturn(new CalendarScheduleLookup.NotFound());
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        assertThat(service().getMonth(USER_ID, YearMonth.of(2026, 9)).events()).isEmpty();

        when(scheduleProvider.loadMovie("550", "BR", "pt-BR")).thenReturn(new CalendarScheduleLookup.Unavailable());
        assertThatThrownBy(() -> service().getMonth(USER_ID, YearMonth.of(2026, 9)))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    void groupsSeriesOnlyWhenTheSeriesCompletenessMarkerIsPresent() {
        CalendarScheduleKey key = key(ContentType.SERIES, "1396");
        CalendarInterest interest = interest(Map.of(key, Set.of(CalendarSource.WATCHLIST)));
        List<CalendarScheduleSnapshot> episodes = List.of(
                episode("1396", 1, 1, LocalDate.of(2026, 9, 15)),
                episode("1396", 1, 2, LocalDate.of(2026, 9, 15)));
        when(interestReader.read(USER_ID)).thenReturn(interest);
        when(snapshotStore.findForInterest(interest, "BR", "pt-BR")).thenReturn(
                new CalendarScheduleReadModel(episodes, new CalendarAssemblyInput.Completeness(Set.of(), Set.of(key))),
                new CalendarScheduleReadModel(episodes, CalendarAssemblyInput.Completeness.empty()));
        when(watchedReader.readWatchedKeys(any(), any())).thenReturn(Set.of());

        assertThat(service().getMonth(USER_ID, YearMonth.of(2026, 9)).events())
                .extracting(event -> event.eventType()).containsExactly(CalendarEventType.SERIES);
        assertThat(service().getMonth(USER_ID, YearMonth.of(2026, 9)).events())
                .extracting(event -> event.eventType()).containsExactly(CalendarEventType.EPISODE, CalendarEventType.EPISODE);
    }

    private CalendarService service() {
        return new CalendarServiceImpl(interestReader, snapshotStore, scheduleProvider, synchronizer, watchedReader, CLOCK);
    }

    private static CalendarInterest interest(Map<CalendarScheduleKey, Set<CalendarSource>> sources) {
        return new CalendarInterest(List.of(), List.of(), List.of(), sources, "pt-BR", "BR");
    }

    private static CalendarScheduleKey key(ContentType type, String id) {
        return new CalendarScheduleKey(type, id, "pt-BR", "BR");
    }

    private static CalendarScheduleSnapshot movie(String id, LocalDate date) {
        return CalendarScheduleSnapshot.builder().eventType(CalendarScheduleSnapshot.EventType.MOVIE).tmdbId(id)
                .region("BR").language("pt-BR").releaseDate(date).title("Movie " + id)
                .lastCheckedAt(LocalDateTime.of(2026, 9, 11, 10, 0)).nextCheckAt(LocalDateTime.MAX)
                .presentInLastTmdbSnapshot(true).build();
    }

    private static CalendarScheduleSnapshot episode(String seriesId, int season, int episode, LocalDate date) {
        return CalendarScheduleSnapshot.builder().eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesId).seasonNumber(season).episodeNumber(episode).region("BR").language("pt-BR")
                .releaseDate(date).title("Episode " + episode).seriesTitle("Series " + seriesId)
                .lastCheckedAt(LocalDateTime.of(2026, 9, 11, 10, 0)).nextCheckAt(LocalDateTime.MAX)
                .presentInLastTmdbSnapshot(true).build();
    }
}
