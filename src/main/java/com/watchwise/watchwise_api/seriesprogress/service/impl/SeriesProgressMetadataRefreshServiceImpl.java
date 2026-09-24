package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressMetadata;
import com.watchwise.watchwise_api.seriesprogress.entity.SeriesProgressSeasonMetadata;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressMetadataRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressSeasonMetadataRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataCalculator;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SeriesProgressMetadataRefreshServiceImpl implements SeriesProgressMetadataRefreshService {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
    private static final long SNAPSHOT_TTL_HOURS = 24;
    private static final long RUNTIME_BASELINE_TTL_HOURS = 168;

    private final TmdbClient tmdbClient;
    private final SeriesProgressMetadataRepository metadataRepository;
    private final SeriesProgressSeasonMetadataRepository seasonMetadataRepository;
    private final SeriesProgressMetadataCalculator calculator;
    private final ExecutorService seasonFetchExecutor;
    private final ExecutorService refreshExecutor;
    private final NewTransactionExecutor newTransactionExecutor;
    private final ConcurrentHashMap<String, CompletableFuture<Snapshot>> refreshes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDate> retryAfter = new ConcurrentHashMap<>();

    public SeriesProgressMetadataRefreshServiceImpl(
            TmdbClient tmdbClient,
            SeriesProgressMetadataRepository metadataRepository,
            SeriesProgressSeasonMetadataRepository seasonMetadataRepository,
            SeriesProgressMetadataCalculator calculator,
            @Qualifier("tmdbSeasonFetchExecutor") ExecutorService seasonFetchExecutor,
            @Qualifier("calendarScheduleRefreshExecutor") ExecutorService refreshExecutor,
            NewTransactionExecutor newTransactionExecutor) {
        this.tmdbClient = tmdbClient;
        this.metadataRepository = metadataRepository;
        this.seasonMetadataRepository = seasonMetadataRepository;
        this.calculator = calculator;
        this.seasonFetchExecutor = seasonFetchExecutor;
        this.refreshExecutor = refreshExecutor;
        this.newTransactionExecutor = newTransactionExecutor;
    }

    @Override
    public Snapshot refreshIfMissingOrExpired(String seriesTmdbId, LocalDate today) {
        requireArguments(seriesTmdbId, today);
        return singleFlight(seriesTmdbId, () -> {
            Snapshot current = readSnapshot(seriesTmdbId);
            if (isFresh(current, today)) {
                return current;
            }
            return refreshFromTmdb(seriesTmdbId, today, current);
        });
    }

    @Override
    public Map<String, Snapshot> getSnapshotsForRead(Collection<String> seriesTmdbIds, LocalDate today) {
        Objects.requireNonNull(seriesTmdbIds, "seriesTmdbIds must not be null");
        requireArguments("batch", today);
        List<String> ids = seriesTmdbIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<String, Snapshot> existing = readSnapshots(ids);
        Map<String, Snapshot> result = new LinkedHashMap<>();
        for (String seriesTmdbId : ids) {
            Snapshot current = existing.get(seriesTmdbId);
            if (current == null) {
                result.put(seriesTmdbId, refreshIfMissingOrExpired(seriesTmdbId, today));
            } else if (isFresh(current, today) || isRetrySuppressed(seriesTmdbId, today)) {
                result.put(seriesTmdbId, current);
            } else {
                result.put(seriesTmdbId, current);
                scheduleRefresh(seriesTmdbId, today, current);
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public Snapshot refresh(String seriesTmdbId, TmdbTvFullDetails tvDetails, LocalDate today) {
        requireArguments(seriesTmdbId, today);
        Objects.requireNonNull(tvDetails, "tvDetails must not be null");
        validateTvDetailsId(seriesTmdbId, tvDetails);
        return singleFlight(seriesTmdbId,
                () -> refreshLoaded(seriesTmdbId, tvDetails, today, readSnapshot(seriesTmdbId)));
    }

    private Snapshot refreshLoaded(
            String seriesTmdbId,
            TmdbTvFullDetails tvDetails,
            LocalDate today,
            Snapshot previous) {
        List<Integer> seasonNumbers = regularSeasonNumbers(tvDetails);
        List<CompletableFuture<Optional<LoadedSeason>>> seasonLoads = seasonNumbers.stream()
                .map(seasonNumber -> CompletableFuture.supplyAsync(
                        () -> loadSeason(seriesTmdbId, seasonNumber), seasonFetchExecutor))
                .toList();
        List<LoadedSeason> loadedSeasons = seasonLoads.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
        boolean completeLoad = tvDetails.seasons() != null
                && loadedSeasons.size() == seasonNumbers.size()
                && loadedSeasons.stream().allMatch(loaded -> loaded.details().episodes() != null);
        SeriesProgressMetadataCalculator.CalculatedSnapshot calculated = calculator.calculate(
                seriesTmdbId,
                loadedSeasons.stream().map(LoadedSeason::details).toList(),
                today);
        if (!completeLoad) {
            return previous == null ? unavailable() : previous;
        }
        return persist(seriesTmdbId, calculated);
    }

    private Optional<LoadedSeason> loadSeason(String seriesTmdbId, Integer seasonNumber) {
        TmdbLookupResult<TmdbSeasonFullDetails> lookup = tmdbClient.getSeasonFullDetails(
                seriesTmdbId, seasonNumber, LANGUAGE);
        if (lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found) {
            return Optional.of(new LoadedSeason(found.value()));
        }
        return Optional.empty();
    }

    private Snapshot persist(
            String seriesTmdbId,
            SeriesProgressMetadataCalculator.CalculatedSnapshot calculated) {
        LocalDateTime refreshedAt = LocalDateTime.now();
        return newTransactionExecutor.runInNewTransaction(() -> {
            SeriesProgressMetadata current = metadataRepository.findById(seriesTmdbId).orElse(null);
            SeriesProgressMetadata metadata = SeriesProgressMetadata.builder()
                    .seriesTmdbId(seriesTmdbId)
                    .regularReleasedEpisodeCount(calculated.series().regularReleasedEpisodeCount())
                    .totalKnownRuntime(calculated.series().totalKnownRuntime())
                    .knownRuntimeEpisodeCount(calculated.series().knownRuntimeEpisodeCount())
                    .lastReleasedEpisodeDate(calculated.series().lastReleasedEpisodeDate())
                    .refreshedAt(refreshedAt)
                    .runtimeVerifiedAt(calculated.series().runtimeComplete() ? refreshedAt : null)
                    .build();
            metadataRepository.save(metadata);

            seasonMetadataRepository.deleteAllBySeriesTmdbIdIn(List.of(seriesTmdbId));
            List<SeriesProgressSeasonMetadata> seasons = calculated.seasons().stream()
                    .map(season -> SeriesProgressSeasonMetadata.builder()
                            .seriesTmdbId(seriesTmdbId)
                            .seasonNumber(season.seasonNumber())
                            .regularReleasedEpisodeCount(season.regularReleasedEpisodeCount())
                            .totalKnownRuntime(season.totalKnownRuntime())
                            .knownRuntimeEpisodeCount(season.knownRuntimeEpisodeCount())
                            .lastReleasedEpisodeDate(season.lastReleasedEpisodeDate())
                            .refreshedAt(refreshedAt)
                            .build())
                    .toList();
            seasonMetadataRepository.saveAll(seasons);
            return toSnapshot(metadata, seasons);
        });
    }

    private Snapshot readSnapshot(String seriesTmdbId) {
        Optional<SeriesProgressMetadata> metadata = metadataRepository.findById(seriesTmdbId);
        if (metadata.isEmpty()) {
            return null;
        }
        List<SeasonSnapshot> seasons = seasonMetadataRepository.findAllBySeriesTmdbIdIn(List.of(seriesTmdbId)).stream()
                .filter(season -> season.getSeasonNumber() != null && season.getSeasonNumber() > 0)
                .sorted(Comparator.comparing(SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection::getSeasonNumber))
                .map(season -> new SeasonSnapshot(
                        season.getSeriesTmdbId(),
                        season.getSeasonNumber(),
                        season.getRegularReleasedEpisodeCount(),
                        season.getTotalKnownRuntime(),
                        season.getKnownRuntimeEpisodeCount(),
                        season.getLastReleasedEpisodeDate(),
                        season.getRefreshedAt()))
                .toList();
        return new Snapshot(toSeriesSnapshot(metadata.get()), seasons);
    }

    private Map<String, Snapshot> readSnapshots(Collection<String> seriesTmdbIds) {
        List<SeriesProgressMetadataRepository.SeriesProgressMetadataProjection> metadata =
                metadataRepository.findAllBySeriesTmdbIdIn(seriesTmdbIds);
        List<SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection> seasons =
                seasonMetadataRepository.findAllBySeriesTmdbIdIn(seriesTmdbIds);
        Map<String, List<SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection>> seasonsBySeries =
                seasons.stream().collect(Collectors.groupingBy(
                        SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection::getSeriesTmdbId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        for (SeriesProgressMetadataRepository.SeriesProgressMetadataProjection row : metadata) {
            List<SeasonSnapshot> seasonSnapshots = seasonsBySeries
                    .getOrDefault(row.getSeriesTmdbId(), List.of())
                    .stream()
                    .filter(season -> season.getSeasonNumber() != null && season.getSeasonNumber() > 0)
                    .sorted(Comparator.comparing(SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection::getSeasonNumber))
                    .map(this::toSeasonSnapshot)
                    .toList();
            snapshots.put(row.getSeriesTmdbId(), new Snapshot(toSeriesSnapshot(row), seasonSnapshots));
        }
        return snapshots;
    }

    private SeasonSnapshot toSeasonSnapshot(
            SeriesProgressSeasonMetadataRepository.SeriesProgressSeasonMetadataProjection season) {
        return new SeasonSnapshot(
                season.getSeriesTmdbId(),
                season.getSeasonNumber(),
                season.getRegularReleasedEpisodeCount(),
                season.getTotalKnownRuntime(),
                season.getKnownRuntimeEpisodeCount(),
                season.getLastReleasedEpisodeDate(),
                season.getRefreshedAt());
    }

    private Snapshot toSnapshot(SeriesProgressMetadata metadata, List<SeriesProgressSeasonMetadata> seasons) {
        return new Snapshot(
                toSeriesSnapshot(metadata),
                seasons.stream()
                        .sorted(Comparator.comparing(SeriesProgressSeasonMetadata::getSeasonNumber))
                        .map(season -> new SeasonSnapshot(
                                season.getSeriesTmdbId(),
                                season.getSeasonNumber(),
                                season.getRegularReleasedEpisodeCount(),
                                season.getTotalKnownRuntime(),
                                season.getKnownRuntimeEpisodeCount(),
                                season.getLastReleasedEpisodeDate(),
                                season.getRefreshedAt()))
                        .toList());
    }

    private SeriesSnapshot toSeriesSnapshot(SeriesProgressMetadata metadata) {
        return new SeriesSnapshot(
                metadata.getSeriesTmdbId(),
                metadata.getRegularReleasedEpisodeCount(),
                metadata.getTotalKnownRuntime(),
                metadata.getKnownRuntimeEpisodeCount(),
                metadata.getLastReleasedEpisodeDate(),
                metadata.getRefreshedAt(),
                metadata.getRuntimeVerifiedAt());
    }

    private SeriesSnapshot toSeriesSnapshot(
            SeriesProgressMetadataRepository.SeriesProgressMetadataProjection metadata) {
        return new SeriesSnapshot(
                metadata.getSeriesTmdbId(),
                metadata.getRegularReleasedEpisodeCount(),
                metadata.getTotalKnownRuntime(),
                metadata.getKnownRuntimeEpisodeCount(),
                metadata.getLastReleasedEpisodeDate(),
                metadata.getRefreshedAt(),
                metadata.getRuntimeVerifiedAt());
    }

    private List<Integer> regularSeasonNumbers(TmdbTvFullDetails tvDetails) {
        if (tvDetails.seasons() == null) {
            return List.of();
        }
        return tvDetails.seasons().stream()
                .filter(Objects::nonNull)
                .map(season -> season.seasonNumber())
                .filter(number -> number != null && number > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private boolean isFresh(Snapshot snapshot, LocalDate today) {
        if (snapshot == null || snapshot.series().refreshedAt() == null) {
            return false;
        }
        LocalDateTime freshnessBoundary = today.plusDays(1).atStartOfDay();
        if (snapshot.series().refreshedAt().isBefore(freshnessBoundary.minusHours(SNAPSHOT_TTL_HOURS))
                || snapshot.series().refreshedAt().isAfter(freshnessBoundary)) {
            return false;
        }
        LocalDateTime runtimeVerifiedAt = snapshot.series().runtimeVerifiedAt() == null
                ? snapshot.series().refreshedAt()
                : snapshot.series().runtimeVerifiedAt();
        return !runtimeVerifiedAt.isBefore(freshnessBoundary.minusHours(RUNTIME_BASELINE_TTL_HOURS));
    }

    private Snapshot refreshFromTmdb(String seriesTmdbId, LocalDate today, Snapshot previous) {
        TmdbLookupResult<TmdbTvFullDetails> lookup = tmdbClient.getTvFullDetails(seriesTmdbId, LANGUAGE);
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> found)) {
            return previous == null ? unavailable() : previous;
        }
        validateTvDetailsId(seriesTmdbId, found.value());
        Snapshot refreshed = refreshLoaded(seriesTmdbId, found.value(), today, previous);
        if (refreshed != previous) {
            retryAfter.remove(seriesTmdbId);
        }
        return refreshed;
    }

    private void scheduleRefresh(String seriesTmdbId, LocalDate today, Snapshot previous) {
        if (isRetrySuppressed(seriesTmdbId, today)) {
            return;
        }
        CompletableFuture<Snapshot> task = new CompletableFuture<>();
        if (refreshes.putIfAbsent(seriesTmdbId, task) != null) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    Snapshot refreshed = refreshFromTmdb(seriesTmdbId, today, previous);
                    if (refreshed == previous) {
                        markRetrySuppressed(seriesTmdbId, today, previous);
                    }
                    task.complete(refreshed);
                } catch (RuntimeException | Error exception) {
                    markRetrySuppressed(seriesTmdbId, today, previous);
                    task.complete(previous);
                    log.warn("Series progress metadata refresh failed for {}", seriesTmdbId, exception);
                } finally {
                    refreshes.remove(seriesTmdbId, task);
                }
            });
        } catch (RejectedExecutionException exception) {
            refreshes.remove(seriesTmdbId, task);
            markRetrySuppressed(seriesTmdbId, today, previous);
            log.warn("Series progress metadata refresh was rejected for {}", seriesTmdbId, exception);
        }
    }

    private boolean isRetrySuppressed(String seriesTmdbId, LocalDate today) {
        LocalDate retryDate = retryAfter.get(seriesTmdbId);
        return retryDate != null && today.isBefore(retryDate);
    }

    private void markRetrySuppressed(String seriesTmdbId, LocalDate today, Snapshot previous) {
        long retryDays = previous.series().totalKnownRuntime() != null ? 7 : 1;
        retryAfter.put(seriesTmdbId, today.plusDays(retryDays));
    }

    private void validateTvDetailsId(String seriesTmdbId, TmdbTvFullDetails tvDetails) {
        if (tvDetails == null || !seriesTmdbId.equals(tvDetails.id())) {
            throw new IllegalArgumentException("tvDetails.id() does not match seriesTmdbId");
        }
    }

    private Snapshot singleFlight(String seriesTmdbId, Supplier<Snapshot> action) {
        CompletableFuture<Snapshot> created = new CompletableFuture<>();
        CompletableFuture<Snapshot> shared = refreshes.putIfAbsent(seriesTmdbId, created);
        if (shared != null) {
            return await(shared);
        }
        try {
            Snapshot result = action.get();
            created.complete(result);
            return result;
        } catch (RuntimeException | Error exception) {
            created.completeExceptionally(exception);
            throw exception;
        } finally {
            refreshes.remove(seriesTmdbId, created);
        }
    }

    private Snapshot await(CompletableFuture<Snapshot> shared) {
        try {
            return shared.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private void requireArguments(String seriesTmdbId, LocalDate today) {
        if (seriesTmdbId == null || seriesTmdbId.isBlank()) {
            throw new IllegalArgumentException("seriesTmdbId must not be blank");
        }
        Objects.requireNonNull(today, "today must not be null");
    }

    private Snapshot unavailable() {
        throw new TmdbUnavailableException("TMDB is currently unavailable");
    }

    private record LoadedSeason(TmdbSeasonFullDetails details) {
    }

}
