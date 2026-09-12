package com.watchwise.watchwise_api.calendar.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CalendarScheduleSynchronizerImplTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-12T10:00:00Z");

    @Test
    @DisplayName("[synchronize] Should Do Nothing - When The Schedule Came From Cache")
    void shouldDoNothingWhenTheScheduleCameFromCache() {
        CalendarScheduleSnapshotStore store = mock(CalendarScheduleSnapshotStore.class);
        CalendarScheduleSynchronizerImpl synchronizer = synchronizer(store);

        synchronizer.synchronize(movieBatch(TmdbLookupOrigin.CACHE, LocalDate.of(2026, 9, 20)), CHECKED_AT);

        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("[synchronize] Should Upsert A Remote Movie And Invalidate Only Its Schedule Entry")
    void shouldUpsertARemoteMovieAndInvalidateOnlyItsScheduleEntry() {
        CalendarScheduleSnapshotStore store = mock(CalendarScheduleSnapshotStore.class);
        Cache<String, TmdbLookupResult<TmdbMovieReleaseDates>> movieCache = Caffeine.newBuilder().build();
        Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> seasonCache = Caffeine.newBuilder().build();
        movieCache.put("550|pt-BR", new TmdbLookupResult.NotFound<>());
        seasonCache.put("1396|2|pt-BR", new TmdbLookupResult.NotFound<>());
        when(store.upsertMovie(any())).thenReturn(true);
        CalendarScheduleSynchronizerImpl synchronizer = new CalendarScheduleSynchronizerImpl(store, movieCache, seasonCache);

        synchronizer.synchronize(movieBatch(TmdbLookupOrigin.REMOTE, LocalDate.of(2026, 9, 20)), CHECKED_AT);

        ArgumentCaptor<CalendarMovieSchedule> schedule = ArgumentCaptor.forClass(CalendarMovieSchedule.class);
        verify(store).upsertMovie(schedule.capture());
        assertThat(schedule.getValue().lastCheckedAt()).isEqualTo(CHECKED_AT);
        assertThat(schedule.getValue().nextCheckAt()).isEqualTo(Instant.parse("2026-09-19T10:00:00Z"));
        assertThat(movieCache.getIfPresent("550|pt-BR")).isNull();
        assertThat(seasonCache.getIfPresent("1396|2|pt-BR")).isNotNull();
    }

    @Test
    @DisplayName("[synchronize] Should Reconcile A Remote Season And Use Released Cadence")
    void shouldReconcileARemoteSeasonAndUseReleasedCadence() {
        CalendarScheduleSnapshotStore store = mock(CalendarScheduleSnapshotStore.class);
        CalendarScheduleSynchronizerImpl synchronizer = synchronizer(store);

        synchronizer.synchronize(seasonBatch(TmdbLookupOrigin.REMOTE, LocalDate.of(2026, 9, 1)), CHECKED_AT);

        ArgumentCaptor<CalendarSeasonSchedule> schedule = ArgumentCaptor.forClass(CalendarSeasonSchedule.class);
        verify(store).reconcileSeason(schedule.capture());
        assertThat(schedule.getValue().episodes().getFirst().nextCheckAt()).isEqualTo(Instant.MAX);
    }

    @Test
    @DisplayName("[synchronize] Should Keep The Schedule Cache Entry - When A Remote Snapshot Is Unchanged")
    void shouldKeepTheScheduleCacheEntryWhenARemoteSnapshotIsUnchanged() {
        CalendarScheduleSnapshotStore store = mock(CalendarScheduleSnapshotStore.class);
        Cache<String, TmdbLookupResult<TmdbMovieReleaseDates>> movieCache = Caffeine.newBuilder().build();
        Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> seasonCache = Caffeine.newBuilder().build();
        movieCache.put("550|pt-BR", new TmdbLookupResult.NotFound<>());
        CalendarScheduleSynchronizerImpl synchronizer = new CalendarScheduleSynchronizerImpl(store, movieCache, seasonCache);

        synchronizer.synchronize(movieBatch(TmdbLookupOrigin.REMOTE, LocalDate.of(2026, 9, 20)), CHECKED_AT);

        assertThat(movieCache.getIfPresent("550|pt-BR")).isNotNull();
    }

    @Test
    @DisplayName("[upsertMovie] Should Avoid A Write - When A Remote Snapshot Is Unchanged")
    void shouldAvoidAWriteWhenARemoteSnapshotIsUnchanged() {
        CalendarScheduleSnapshotRepository repository = mock(CalendarScheduleSnapshotRepository.class);
        CalendarScheduleSnapshot existing = movieSnapshot(LocalDate.of(2026, 9, 20));
        when(repository.findByMovieIdentity("550", "BR", "pt-BR")).thenReturn(Optional.of(existing));
        CalendarScheduleSnapshotStore store = store(repository);

        store.upsertMovie(movieSchedule(LocalDate.of(2026, 9, 20), CHECKED_AT));

        ArgumentCaptor<CalendarScheduleSnapshot> saved = ArgumentCaptor.forClass(CalendarScheduleSnapshot.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReleaseDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(saved.getValue().getLastCheckedAt()).isEqualTo(LocalDateTime.of(2026, 9, 12, 10, 0));
    }

    @Test
    @DisplayName("[upsertMovie] Should Replace The Release Date - When TMDB Reports A Different Date")
    void shouldReplaceTheReleaseDateWhenTmdbReportsADifferentDate() {
        CalendarScheduleSnapshotRepository repository = mock(CalendarScheduleSnapshotRepository.class);
        when(repository.findByMovieIdentity("550", "BR", "pt-BR"))
                .thenReturn(Optional.of(movieSnapshot(LocalDate.of(2026, 9, 20))));
        CalendarScheduleSnapshotStore store = store(repository);

        store.upsertMovie(movieSchedule(LocalDate.of(2026, 10, 3), CHECKED_AT));

        ArgumentCaptor<CalendarScheduleSnapshot> saved = ArgumentCaptor.forClass(CalendarScheduleSnapshot.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReleaseDate()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("[upsertMovie] Should Preserve The Previous Date - When A Fresh Payload Has No Usable Date")
    void shouldPreserveThePreviousDateWhenAFreshPayloadHasNoUsableDate() {
        CalendarScheduleSnapshotRepository repository = mock(CalendarScheduleSnapshotRepository.class);
        when(repository.findByMovieIdentity("550", "BR", "pt-BR"))
                .thenReturn(Optional.of(movieSnapshot(LocalDate.of(2026, 9, 20))));
        CalendarScheduleSnapshotStore store = store(repository);

        store.upsertMovie(movieSchedule(null, CHECKED_AT));

        ArgumentCaptor<CalendarScheduleSnapshot> saved = ArgumentCaptor.forClass(CalendarScheduleSnapshot.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReleaseDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("[reconcileSeason] Should Remove Missing Episode Coordinates - When The Remote Season Changed")
    void shouldRemoveMissingEpisodeCoordinatesWhenTheRemoteSeasonChanged() {
        CalendarScheduleSnapshotRepository repository = mock(CalendarScheduleSnapshotRepository.class);
        CalendarScheduleSnapshot retained = episodeSnapshot(1, LocalDate.of(2026, 9, 20));
        CalendarScheduleSnapshot removed = episodeSnapshot(2, LocalDate.of(2026, 9, 21));
        when(repository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE, "1396", 2, "BR", "pt-BR"))
                .thenReturn(List.of(retained, removed));
        when(repository.findByEpisodeIdentity("1396", 2, 1, "BR", "pt-BR")).thenReturn(Optional.of(retained));
        CalendarScheduleSnapshotStore store = store(repository);

        store.reconcileSeason(seasonSchedule(LocalDate.of(2026, 9, 20), CHECKED_AT));

        verify(repository).deleteAll(List.of(removed));
    }

    @Test
    @DisplayName("[reconcileSeason] Should Retry The Full Season - When A Concurrent First Insert Wins")
    void shouldRetryTheFullSeasonWhenAConcurrentFirstInsertWins() {
        CalendarScheduleSnapshotRepository repository = mock(CalendarScheduleSnapshotRepository.class);
        CalendarScheduleSnapshot winner = episodeSnapshot(1, LocalDate.of(2026, 9, 20));
        when(repository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE, "1396", 2, "BR", "pt-BR"))
                .thenReturn(List.of());
        when(repository.findByEpisodeIdentity("1396", 2, 1, "BR", "pt-BR"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate episode identity"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CalendarScheduleSnapshotStore store = store(repository);

        boolean changed = store.reconcileSeason(seasonSchedule(LocalDate.of(2026, 9, 20), CHECKED_AT));

        assertThat(changed).isTrue();
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("[upsertMovie] Should Preserve The Same Snapshot Facts - When Synchronization Repeats")
    void shouldPreserveTheSameSnapshotFactsWhenSynchronizationRepeats() {
        CalendarScheduleSnapshotRepository repository = mock(CalendarScheduleSnapshotRepository.class);
        CalendarScheduleSnapshot existing = movieSnapshot(LocalDate.of(2026, 9, 20));
        when(repository.findByMovieIdentity("550", "BR", "pt-BR")).thenReturn(Optional.of(existing));
        CalendarScheduleSnapshotStore store = store(repository);

        store.upsertMovie(movieSchedule(LocalDate.of(2026, 9, 20), CHECKED_AT));
        store.upsertMovie(movieSchedule(LocalDate.of(2026, 9, 20), CHECKED_AT));

        ArgumentCaptor<CalendarScheduleSnapshot> saved = ArgumentCaptor.forClass(CalendarScheduleSnapshot.class);
        verify(repository, times(2)).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).extracting(CalendarScheduleSnapshot::getReleaseDate)
                .containsOnly(LocalDate.of(2026, 9, 20));
        assertThat(saved.getAllValues()).extracting(CalendarScheduleSnapshot::getNextCheckAt)
                .containsOnly(LocalDateTime.of(2026, 9, 19, 10, 0));
    }

    private static CalendarScheduleSnapshotStore store(CalendarScheduleSnapshotRepository repository) {
        NewTransactionExecutor transactionExecutor = mock(NewTransactionExecutor.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(transactionExecutor).runInNewTransaction(any());
        return new CalendarScheduleSnapshotStore(repository, transactionExecutor);
    }

    private static CalendarScheduleSynchronizerImpl synchronizer(CalendarScheduleSnapshotStore store) {
        return new CalendarScheduleSynchronizerImpl(store, Caffeine.newBuilder().build(), Caffeine.newBuilder().build());
    }

    private static CalendarScheduleBatch movieBatch(TmdbLookupOrigin origin, LocalDate releaseDate) {
        CalendarScheduleKey key = new CalendarScheduleKey(ContentType.MOVIE, "550", "pt-BR", "BR");
        return CalendarScheduleBatch.movie(key, origin, movieSchedule(releaseDate, null));
    }

    private static CalendarScheduleBatch seasonBatch(TmdbLookupOrigin origin, LocalDate releaseDate) {
        CalendarScheduleKey key = new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR");
        return CalendarScheduleBatch.season(key, origin, seasonSchedule(releaseDate, null));
    }

    private static CalendarMovieSchedule movieSchedule(LocalDate releaseDate, Instant checkedAt) {
        return new CalendarMovieSchedule("550", "BR", "pt-BR", releaseDate, "Fight Club", "/fight-club.jpg",
                checkedAt, null);
    }

    private static CalendarSeasonSchedule seasonSchedule(LocalDate releaseDate, Instant checkedAt) {
        return new CalendarSeasonSchedule("1396", 2, "BR", "pt-BR", "Breaking Bad", "/breaking-bad.jpg",
                List.of(new CalendarEpisodeSchedule(1, "Seven Thirty-Seven", releaseDate, "/one.jpg", checkedAt, null)));
    }

    private static CalendarScheduleSnapshot movieSnapshot(LocalDate releaseDate) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                .tmdbId("550")
                .region("BR")
                .language("pt-BR")
                .releaseDate(releaseDate)
                .title("Fight Club")
                .posterPath("/fight-club.jpg")
                .lastCheckedAt(LocalDateTime.of(2026, 9, 11, 10, 0))
                .nextCheckAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private static CalendarScheduleSnapshot episodeSnapshot(Integer episodeNumber, LocalDate releaseDate) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId("1396")
                .seasonNumber(2)
                .episodeNumber(episodeNumber)
                .region("BR")
                .language("pt-BR")
                .releaseDate(releaseDate)
                .title("Episode " + episodeNumber)
                .seriesTitle("Breaking Bad")
                .lastCheckedAt(LocalDateTime.of(2026, 9, 11, 10, 0))
                .nextCheckAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .presentInLastTmdbSnapshot(true)
                .build();
    }
}
