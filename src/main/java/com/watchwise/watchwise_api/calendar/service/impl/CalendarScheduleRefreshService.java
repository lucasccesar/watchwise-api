package com.watchwise.watchwise_api.calendar.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class CalendarScheduleRefreshService {

    private static final Logger log = LoggerFactory.getLogger(CalendarScheduleRefreshService.class);
    private static final Duration SEASON_REFRESH_RETRY_DELAY = Duration.ofMinutes(1);

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final CalendarScheduleSnapshotStore snapshotStore;
    private final CalendarScheduleProvider scheduleProvider;
    private final CalendarScheduleSynchronizer scheduleSynchronizer;
    private final ExecutorService calendarScheduleRefreshExecutor;
    private final Clock clock;
    private final Set<RefreshKey> refreshesInFlight = ConcurrentHashMap.newKeySet();
    private final Cache<RefreshKey, Boolean> failedSeasonRefreshes = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(SEASON_REFRESH_RETRY_DELAY)
            .build();
    private final Cache<RefreshKey, Boolean> failedSeriesRefreshes = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(SEASON_REFRESH_RETRY_DELAY)
            .build();

    public CalendarScheduleRefreshService(
            WatchlistEntryRepository watchlistEntryRepository,
            CalendarScheduleSnapshotStore snapshotStore,
            CalendarScheduleProvider scheduleProvider,
            CalendarScheduleSynchronizer scheduleSynchronizer,
            @Qualifier("calendarScheduleRefreshExecutor") ExecutorService calendarScheduleRefreshExecutor,
            Clock clock) {
        this.watchlistEntryRepository = watchlistEntryRepository;
        this.snapshotStore = snapshotStore;
        this.scheduleProvider = scheduleProvider;
        this.scheduleSynchronizer = scheduleSynchronizer;
        this.calendarScheduleRefreshExecutor = calendarScheduleRefreshExecutor;
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
        Set<CalendarScheduleKey> discoveryKeys = snapshotStore.findSeriesDueForDiscovery(activeKeys, checkedAt);
        Map<RefreshKey, RefreshKey> uniqueRefreshKeys = new LinkedHashMap<>();
        discoveryKeys.forEach(key -> {
            RefreshKey refreshKey = new RefreshKey(key, null, true);
            uniqueRefreshKeys.putIfAbsent(refreshKey, refreshKey);
        });
        for (CalendarScheduleSnapshot snapshot : dueSnapshots) {
            toRefreshKey(snapshot, activeKeys, discoveryKeys)
                    .ifPresent(key -> uniqueRefreshKeys.putIfAbsent(key, key));
        }
        if (uniqueRefreshKeys.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> refreshes = uniqueRefreshKeys.keySet().stream()
                .map(key -> CompletableFuture.runAsync(() -> refreshOne(key, checkedAt), calendarScheduleRefreshExecutor))
                .toList();
        for (CompletableFuture<Void> refresh : refreshes) {
            try {
                refresh.join();
            } catch (CompletionException exception) {
                log.warn("Calendar schedule refresh task failed", exception.getCause());
            }
        }
    }

    public void refreshSeasonInBackground(
            String seriesTmdbId, Integer seasonNumber, String region, String language) {
        if (seriesTmdbId == null || seriesTmdbId.isBlank() || seasonNumber == null || seasonNumber <= 0) {
            return;
        }
        CalendarScheduleKey key = new CalendarScheduleKey(
                ContentType.SERIES, seriesTmdbId, language, region);
        RefreshKey refreshKey = new RefreshKey(key, seasonNumber, false);
        if (failedSeasonRefreshes.getIfPresent(refreshKey) != null
                || !refreshesInFlight.add(refreshKey)) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    if (!refreshOneReserved(refreshKey, clock.instant())) {
                        failedSeasonRefreshes.put(refreshKey, true);
                    }
                } finally {
                    refreshesInFlight.remove(refreshKey);
                }
            }, calendarScheduleRefreshExecutor);
        } catch (RejectedExecutionException exception) {
            refreshesInFlight.remove(refreshKey);
            failedSeasonRefreshes.put(refreshKey, true);
            log.warn("Calendar season refresh was rejected for {}", refreshKey, exception);
        }
    }

    public boolean refreshSeriesNow(String seriesTmdbId, String region, String language) {
        CalendarScheduleKey key = new CalendarScheduleKey(ContentType.SERIES, seriesTmdbId, language, region);
        RefreshKey refreshKey = new RefreshKey(key, null, true);
        if (failedSeriesRefreshes.getIfPresent(refreshKey) != null
                || !refreshesInFlight.add(refreshKey)) {
            return false;
        }

        boolean refreshed = false;
        try {
            CalendarScheduleLookup lookup = scheduleProvider.loadSeriesFresh(seriesTmdbId, region, language);
            if (lookup instanceof CalendarScheduleLookup.FoundSeries foundSeries
                    && foundSeries.schedule().hasRemoteResults()) {
                scheduleSynchronizer.synchronizeSeries(foundSeries.schedule(), clock.instant());
                refreshed = true;
                return true;
            }
            if (lookup instanceof CalendarScheduleLookup.NotFound notFound
                    && notFound.seasonNumber() == null) {
                snapshotStore.invalidate(key, clock.instant(), true);
            }
            return false;
        } catch (RuntimeException exception) {
            log.warn("Current calendar series refresh failed for {}", refreshKey, exception);
            return false;
        } finally {
            refreshesInFlight.remove(refreshKey);
            if (!refreshed) {
                failedSeriesRefreshes.put(refreshKey, true);
            }
        }
    }

    private void refreshOne(RefreshKey refreshKey, Instant checkedAt) {
        if (!refreshesInFlight.add(refreshKey)) {
            return;
        }
        try {
            refreshOneReserved(refreshKey, checkedAt);
        } finally {
            refreshesInFlight.remove(refreshKey);
        }
    }

    private boolean refreshOneReserved(RefreshKey refreshKey, Instant checkedAt) {
        try {
            CalendarScheduleKey key = refreshKey.scheduleKey();
            CalendarScheduleLookup lookup;
            if (key.type() == ContentType.MOVIE) {
                lookup = scheduleProvider.loadMovie(key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
            } else if (refreshKey.discovery()) {
                lookup = scheduleProvider.loadSeries(key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
            } else {
                lookup = scheduleProvider.loadSeason(
                        key.tmdbId(), refreshKey.seasonNumber(), key.preferredRegion(), key.preferredLanguage());
            }
            if (lookup instanceof CalendarScheduleLookup.Found found
                    && found.batch().origin() == TmdbLookupOrigin.REMOTE) {
                scheduleSynchronizer.synchronize(found.batch(), checkedAt);
                return true;
            }
            if (lookup instanceof CalendarScheduleLookup.FoundSeries foundSeries
                    && foundSeries.schedule().hasRemoteResults()) {
                scheduleSynchronizer.synchronizeSeries(foundSeries.schedule(), checkedAt);
                return true;
            }
            if (lookup instanceof CalendarScheduleLookup.NotFound notFound) {
                if (key.type() == ContentType.SERIES && notFound.seasonNumber() != null) {
                    snapshotStore.invalidateSeason(key, notFound.seasonNumber(), checkedAt);
                } else {
                    snapshotStore.invalidate(key, checkedAt, refreshKey.discovery());
                }
            }
            return false;
        } catch (RuntimeException exception) {
            log.warn("Calendar schedule refresh failed for {}", refreshKey, exception);
            return false;
        }
    }

    private Optional<RefreshKey> toRefreshKey(
            CalendarScheduleSnapshot snapshot,
            Set<CalendarScheduleKey> activeKeys,
            Set<CalendarScheduleKey> discoveryKeys) {
        try {
            if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE) {
                CalendarScheduleKey key = new CalendarScheduleKey(
                        ContentType.MOVIE,
                        snapshot.getTmdbId(),
                        snapshot.getLanguage(),
                        snapshot.getRegion());
                return activeKeys.contains(key) ? Optional.of(new RefreshKey(key, null, false)) : Optional.empty();
            }
            if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.EPISODE) {
                CalendarScheduleKey key = new CalendarScheduleKey(
                        ContentType.SERIES,
                        snapshot.getSeriesTmdbId(),
                        snapshot.getLanguage(),
                        snapshot.getRegion());
                if (!activeKeys.contains(key) || discoveryKeys.contains(key)) {
                    return Optional.empty();
                }
                return Optional.of(new RefreshKey(key, snapshot.getSeasonNumber(), false));
            }
        } catch (IllegalArgumentException exception) {
            log.warn("Ignoring malformed calendar schedule snapshot {}", snapshot.getId(), exception);
        }
        return Optional.empty();
    }

    private record RefreshKey(CalendarScheduleKey scheduleKey, Integer seasonNumber, boolean discovery) {

        private RefreshKey {
            if (scheduleKey.type() == ContentType.MOVIE && (seasonNumber != null || discovery)) {
                throw new IllegalArgumentException("Movie refresh keys cannot represent discovery or a season");
            }
            if (scheduleKey.type() == ContentType.SERIES
                    && !discovery
                    && (seasonNumber == null || seasonNumber <= 0)) {
                throw new IllegalArgumentException("Series refresh keys require a positive season");
            }
            if (scheduleKey.type() == ContentType.SERIES && discovery && seasonNumber != null) {
                throw new IllegalArgumentException("Series discovery keys cannot represent a season");
            }
        }
    }

}
