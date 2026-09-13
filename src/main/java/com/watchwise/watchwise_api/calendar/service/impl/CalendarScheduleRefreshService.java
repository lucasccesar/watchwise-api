package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleSynchronizer;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

@Service
public class CalendarScheduleRefreshService {

    private static final Logger log = LoggerFactory.getLogger(CalendarScheduleRefreshService.class);

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final CalendarScheduleSnapshotStore snapshotStore;
    private final CalendarScheduleProvider scheduleProvider;
    private final CalendarScheduleSynchronizer scheduleSynchronizer;
    private final ExecutorService tmdbSeasonFetchExecutor;
    private final Clock clock;

    public CalendarScheduleRefreshService(
            WatchlistEntryRepository watchlistEntryRepository,
            CalendarScheduleSnapshotStore snapshotStore,
            CalendarScheduleProvider scheduleProvider,
            CalendarScheduleSynchronizer scheduleSynchronizer,
            @Qualifier("tmdbSeasonFetchExecutor") ExecutorService tmdbSeasonFetchExecutor,
            Clock clock) {
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.snapshotStore = snapshotStore;
        this.scheduleProvider = scheduleProvider;
        this.scheduleSynchronizer = scheduleSynchronizer;
        this.tmdbSeasonFetchExecutor = tmdbSeasonFetchExecutor;
        this.clock = clock;
    }

    public void refreshDueSchedules() {
        Instant checkedAt = clock.instant();
        LocalDateTime dueAt = checkedAt.atOffset(ZoneOffset.UTC).toLocalDateTime();
        Set<CalendarScheduleKey> activeKeys = Set.copyOf(watchlistEntryRepository.findActiveCalendarScheduleKeys());
        if (activeKeys.isEmpty()) {
            return;
        }

        List<CalendarScheduleSnapshot> dueSnapshots = snapshotStore.findDue(activeKeys, dueAt);
        Map<CalendarScheduleKey, CalendarScheduleKey> uniqueRefreshKeys = new LinkedHashMap<>();
        for (CalendarScheduleSnapshot snapshot : dueSnapshots) {
            toRefreshKey(snapshot, activeKeys).ifPresent(key -> uniqueRefreshKeys.putIfAbsent(key, key));
        }
        if (uniqueRefreshKeys.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> refreshes = uniqueRefreshKeys.keySet().stream()
                .map(key -> CompletableFuture.runAsync(() -> refreshOne(key, checkedAt), tmdbSeasonFetchExecutor))
                .toList();
        for (CompletableFuture<Void> refresh : refreshes) {
            try {
                refresh.join();
            } catch (CompletionException exception) {
                log.warn("Calendar schedule refresh task failed", exception.getCause());
            }
        }
    }

    private void refreshOne(CalendarScheduleKey key, Instant checkedAt) {
        try {
            CalendarScheduleLookup lookup = key.type() == ContentType.MOVIE
                    ? scheduleProvider.loadMovie(key.tmdbId(), key.preferredRegion(), key.preferredLanguage())
                    : scheduleProvider.loadSeries(key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
            if (lookup instanceof CalendarScheduleLookup.Found found
                    && found.batch().origin() == TmdbLookupOrigin.REMOTE) {
                scheduleSynchronizer.synchronize(found.batch(), checkedAt);
            }
            if (lookup instanceof CalendarScheduleLookup.FoundSeries foundSeries
                    && foundSeries.schedule().hasRemoteResults()) {
                scheduleSynchronizer.synchronizeSeries(foundSeries.schedule(), checkedAt);
            }
        } catch (RuntimeException exception) {
            log.warn("Calendar schedule refresh failed for {}", key, exception);
        }
    }

    private Optional<CalendarScheduleKey> toRefreshKey(
            CalendarScheduleSnapshot snapshot, Set<CalendarScheduleKey> activeKeys) {
        try {
            if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE) {
                CalendarScheduleKey key = new CalendarScheduleKey(
                        ContentType.MOVIE,
                        snapshot.getTmdbId(),
                        snapshot.getLanguage(),
                        snapshot.getRegion());
                return activeKeys.contains(key) ? Optional.of(key) : Optional.empty();
            }
            if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.EPISODE) {
                CalendarScheduleKey key = new CalendarScheduleKey(
                        ContentType.SERIES,
                        snapshot.getSeriesTmdbId(),
                        snapshot.getLanguage(),
                        snapshot.getRegion());
                return activeKeys.contains(key) ? Optional.of(key) : Optional.empty();
            }
        } catch (IllegalArgumentException exception) {
            log.warn("Ignoring malformed calendar schedule snapshot {}", snapshot.getId(), exception);
        }
        return Optional.empty();
    }

}
