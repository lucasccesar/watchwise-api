package com.watchwise.watchwise_api.contentreleasedatesnapshot.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarMovieReleaseDateSelector;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.entity.ContentReleaseDateSnapshot;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.repository.ContentReleaseDateSnapshotRepository;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ContentReleaseDateSnapshotServiceImpl implements ContentReleaseDateSnapshotService {

    private static final long SNAPSHOT_TTL_HOURS = 24;
    private static final long REFRESH_RETRY_MINUTES = 60;
    private static final long REFRESH_LEASE_MINUTES = 10;
    private static final Logger log = LoggerFactory.getLogger(ContentReleaseDateSnapshotServiceImpl.class);

    private final ContentReleaseDateSnapshotRepository snapshotRepository;
    private final TmdbClient tmdbClient;
    private final NewTransactionExecutor newTransactionExecutor;
    private final ExecutorService snapshotRefreshExecutor;
    private final ExecutorService snapshotResolutionExecutor;
    private final Map<SnapshotKey, Boolean> refreshes = new ConcurrentHashMap<>();

    public ContentReleaseDateSnapshotServiceImpl(
            ContentReleaseDateSnapshotRepository snapshotRepository,
            TmdbClient tmdbClient,
            NewTransactionExecutor newTransactionExecutor,
            @Qualifier("contentReleaseDateSnapshotExecutor") ExecutorService snapshotRefreshExecutor,
            @Qualifier("contentReleaseDateSnapshotResolutionExecutor") ExecutorService snapshotResolutionExecutor) {
        this.snapshotRepository = snapshotRepository;
        this.tmdbClient = tmdbClient;
        this.newTransactionExecutor = newTransactionExecutor;
        this.snapshotRefreshExecutor = snapshotRefreshExecutor;
        this.snapshotResolutionExecutor = snapshotResolutionExecutor;
    }

    @Override
    public WatchlistDateResolution resolve(User owner, List<WatchlistEntry> entries) {
        String region = owner.getPreferredRegion();
        List<WatchlistEntry> movies = entries.stream()
                .filter(entry -> entry.getType() == ContentType.MOVIE)
                .toList();
        List<WatchlistEntry> series = entries.stream()
                .filter(entry -> entry.getType() == ContentType.SERIES)
                .toList();

        Map<SnapshotKey, ContentReleaseDateSnapshot> snapshots = new HashMap<>();
        loadExisting(snapshots, ContentType.MOVIE, region, movies.stream()
                .map(entry -> entry.getContent().getTmdbId()).toList());
        loadExisting(snapshots, ContentType.SERIES, null, series.stream()
                .map(entry -> entry.getContent().getTmdbId()).toList());

        Map<SnapshotKey, MissingSnapshot> missing = new LinkedHashMap<>();
        for (WatchlistEntry entry : entries) {
            Content content = entry.getContent();
            SnapshotKey key = SnapshotKey.of(entry.getType(), content.getTmdbId(), region);
            ContentReleaseDateSnapshot existing = snapshots.get(key);
            if (existing == null) {
                missing.putIfAbsent(key, new MissingSnapshot(key, content.getTmdbId(), entry.getType(), region));
            } else if (isDue(existing)) {
                scheduleRefresh(key, existing);
            }
        }
        resolveMissing(missing, snapshots);

        Map<UUID, LocalDate> dates = new HashMap<>();
        for (WatchlistEntry entry : entries) {
            ContentReleaseDateSnapshot snapshot = snapshots.get(
                    SnapshotKey.of(entry.getType(), entry.getContent().getTmdbId(), region));
            if (snapshot != null && snapshot.getStatus() == ContentReleaseDateSnapshot.Status.FOUND) {
                dates.put(entry.getId(), snapshot.getReleaseDate());
            }
        }
        return new WatchlistDateResolution(Map.copyOf(dates));
    }

    @Override
    public void writeThrough(User owner, Content content) {
        String region = owner.getPreferredRegion();
        if (find(content.getType(), content.getTmdbId(), region)
                .filter(snapshot -> !isDue(snapshot))
                .isPresent()) {
            return;
        }
        fetchAndPersist(content.getTmdbId(), content.getType(), region);
    }

    @Override
    public long countUpcoming(UUID userId, ContentType type, String region, LocalDate today) {
        return snapshotRepository.countUpcomingByUserIdAndType(
                userId, type == null ? null : type.name(), region, today);
    }

    @Override
    public void refreshDue() {
        for (ContentReleaseDateSnapshot snapshot : snapshotRepository.findByNextCheckAtBefore(LocalDateTime.now())) {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseUntil = now.plusMinutes(REFRESH_LEASE_MINUTES);
            if (!claim(snapshot, now, leaseUntil)) {
                continue;
            }
            try {
                fetchAndPersist(snapshot.getTmdbId(), snapshot.getType(), snapshot.getRegion());
            } catch (TmdbUnavailableException exception) {
                markRetry(snapshot.getId(), leaseUntil);
            } catch (RuntimeException exception) {
                log.warn("Release-date snapshot refresh failed for {}", snapshot.getId(), exception);
                markRetry(snapshot.getId(), leaseUntil);
            }
        }
    }

    private void resolveMissing(
            Map<SnapshotKey, MissingSnapshot> missing,
            Map<SnapshotKey, ContentReleaseDateSnapshot> snapshots) {
        if (missing.isEmpty()) {
            return;
        }
        List<Future<ResolvedSnapshot>> futures = missing.values().stream()
                .map(request -> snapshotResolutionExecutor.submit(() -> new ResolvedSnapshot(
                        request.key(), fetchAndPersist(request.tmdbId(), request.type(), request.region))))
                .toList();
        try {
            for (Future<ResolvedSnapshot> future : futures) {
                ResolvedSnapshot resolved = future.get();
                snapshots.put(resolved.key(), resolved.snapshot());
            }
        } catch (InterruptedException exception) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new TmdbUnavailableException("TMDB resolution was interrupted");
        } catch (ExecutionException exception) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Could not resolve release-date snapshot", cause);
        }
    }

    private void loadExisting(
            Map<SnapshotKey, ContentReleaseDateSnapshot> target,
            ContentType type,
            String region,
            Collection<String> tmdbIds) {
        if (tmdbIds.isEmpty()) {
            return;
        }
        List<ContentReleaseDateSnapshot> found = region == null
                ? snapshotRepository.findByTypeAndRegionIsNullAndTmdbIdIn(type, tmdbIds)
                : snapshotRepository.findByTypeAndRegionAndTmdbIdIn(type, region, tmdbIds);
        found.forEach(snapshot -> target.put(
                SnapshotKey.of(snapshot.getType(), snapshot.getTmdbId(), snapshot.getRegion()), snapshot));
    }

    private ContentReleaseDateSnapshot fetchAndPersist(
            String tmdbId, ContentType type, String region) {
        LocalDate releaseDate;
        ContentReleaseDateSnapshot.Status status;
        if (type == ContentType.MOVIE) {
            TmdbLookupResult<TmdbMovieFullDetails> lookup = tmdbClient.getMovieFullDetails(
                    tmdbId, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
            if (lookup.isUnavailable()) {
                throw new TmdbUnavailableException("TMDB is currently unavailable");
            }
            if (lookup.isNotFound()) {
                releaseDate = null;
                status = ContentReleaseDateSnapshot.Status.NOT_FOUND;
            } else {
                TmdbMovieFullDetails details = lookup.toOptional().orElseThrow();
                releaseDate = CalendarMovieReleaseDateSelector.select(details.releaseDates(), region).orElse(null);
                status = releaseDate == null
                        ? ContentReleaseDateSnapshot.Status.NOT_FOUND
                        : ContentReleaseDateSnapshot.Status.FOUND;
            }
        } else if (type == ContentType.SERIES) {
            TmdbLookupResult<TmdbTvFullDetails> lookup = tmdbClient.getTvFullDetails(
                    tmdbId, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
            if (lookup.isUnavailable()) {
                throw new TmdbUnavailableException("TMDB is currently unavailable");
            }
            if (lookup.isNotFound()) {
                releaseDate = null;
                status = ContentReleaseDateSnapshot.Status.NOT_FOUND;
            } else {
                releaseDate = parseDate(lookup.toOptional().orElseThrow().firstAirDate());
                status = releaseDate == null
                        ? ContentReleaseDateSnapshot.Status.NOT_FOUND
                        : ContentReleaseDateSnapshot.Status.FOUND;
            }
        } else {
            return null;
        }

        LocalDateTime checkedAt = LocalDateTime.now();
        try {
            return newTransactionExecutor.runInNewTransaction(() -> persist(
                    tmdbId, type, region, releaseDate, status, checkedAt));
        } catch (DataIntegrityViolationException exception) {
            return newTransactionExecutor.runInNewTransaction(() ->
                    find(type, tmdbId, region).orElseThrow(() -> exception));
        }
    }

    private boolean isDue(ContentReleaseDateSnapshot snapshot) {
        return snapshot.getNextCheckAt() == null
                || !snapshot.getNextCheckAt().isAfter(LocalDateTime.now());
    }

    private void scheduleRefresh(SnapshotKey key, ContentReleaseDateSnapshot previous) {
        if (refreshes.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusMinutes(REFRESH_LEASE_MINUTES);
        if (!claim(previous, now, leaseUntil)) {
            refreshes.remove(key);
            return;
        }
        try {
            snapshotRefreshExecutor.execute(() -> {
                try {
                    fetchAndPersist(previous.getTmdbId(), previous.getType(), previous.getRegion());
                } catch (TmdbUnavailableException exception) {
                    markRetry(previous.getId(), leaseUntil);
                } catch (RuntimeException exception) {
                    log.warn("Release-date snapshot refresh failed for {}", key, exception);
                    markRetry(previous.getId(), leaseUntil);
                } finally {
                    refreshes.remove(key);
                }
            });
        } catch (RejectedExecutionException exception) {
            refreshes.remove(key);
            markRetry(previous.getId(), leaseUntil);
            log.warn("Release-date snapshot refresh was rejected for {}", key, exception);
        }
    }

    private boolean claim(ContentReleaseDateSnapshot snapshot, LocalDateTime now, LocalDateTime leaseUntil) {
        if (snapshot.getId() == null) {
            return false;
        }
        try {
            return newTransactionExecutor.runInNewTransaction(() -> snapshotRepository.claimDue(
                    snapshot.getId(), now, leaseUntil)) > 0;
        } catch (RuntimeException exception) {
            log.warn("Could not claim release-date snapshot {}", snapshot.getId(), exception);
            return false;
        }
    }

    private void markRetry(UUID snapshotId, LocalDateTime leaseUntil) {
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(REFRESH_RETRY_MINUTES);
        try {
            newTransactionExecutor.runInNewTransaction(() -> snapshotRepository.rescheduleClaimed(
                    snapshotId, leaseUntil, retryAt));
        } catch (RuntimeException exception) {
            log.warn("Could not persist release-date snapshot retry time", exception);
        }
    }

    private ContentReleaseDateSnapshot persist(
            String tmdbId,
            ContentType type,
            String region,
            LocalDate releaseDate,
            ContentReleaseDateSnapshot.Status status,
            LocalDateTime checkedAt) {
        ContentReleaseDateSnapshot snapshot = find(type, tmdbId, region)
                .orElseGet(() -> ContentReleaseDateSnapshot.builder()
                        .tmdbId(tmdbId)
                        .type(type)
                        .region(type == ContentType.SERIES ? null : region)
                        .build());
        snapshot.update(releaseDate, status, checkedAt, checkedAt.plusHours(SNAPSHOT_TTL_HOURS));
        return snapshotRepository.saveAndFlush(snapshot);
    }

    private Optional<ContentReleaseDateSnapshot> find(ContentType type, String tmdbId, String region) {
        return type == ContentType.SERIES
                ? snapshotRepository.findByTypeAndRegionIsNullAndTmdbId(type, tmdbId)
                : snapshotRepository.findByTypeAndRegionAndTmdbId(type, region, tmdbId);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private record SnapshotKey(ContentType type, String tmdbId, String region) {
        static SnapshotKey of(ContentType type, String tmdbId, String region) {
            return new SnapshotKey(type, tmdbId, type == ContentType.SERIES ? null : region);
        }
    }

    private record MissingSnapshot(
            SnapshotKey key, String tmdbId, ContentType type, String region) {
    }

    private record ResolvedSnapshot(
            SnapshotKey key, ContentReleaseDateSnapshot snapshot) {
    }
}
