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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

@Service
public class SeriesProgressMetadataRefreshServiceImpl implements SeriesProgressMetadataRefreshService {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    private final TmdbClient tmdbClient;
    private final SeriesProgressMetadataRepository metadataRepository;
    private final SeriesProgressSeasonMetadataRepository seasonMetadataRepository;
    private final SeriesProgressMetadataCalculator calculator;
    private final ExecutorService seasonFetchExecutor;
    private final NewTransactionExecutor newTransactionExecutor;
    private final ConcurrentHashMap<String, CompletableFuture<Snapshot>> refreshes = new ConcurrentHashMap<>();

    public SeriesProgressMetadataRefreshServiceImpl(
            TmdbClient tmdbClient,
            SeriesProgressMetadataRepository metadataRepository,
            SeriesProgressSeasonMetadataRepository seasonMetadataRepository,
            SeriesProgressMetadataCalculator calculator,
            @Qualifier("tmdbSeasonFetchExecutor") ExecutorService seasonFetchExecutor,
            NewTransactionExecutor newTransactionExecutor) {
        this.tmdbClient = tmdbClient;
        this.metadataRepository = metadataRepository;
        this.seasonMetadataRepository = seasonMetadataRepository;
        this.calculator = calculator;
        this.seasonFetchExecutor = seasonFetchExecutor;
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
            TmdbLookupResult<TmdbTvFullDetails> lookup = tmdbClient.getTvFullDetails(seriesTmdbId, LANGUAGE);
            if (!(lookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> found)) {
                return current == null ? unavailable() : current;
            }
            validateTvDetailsId(seriesTmdbId, found.value());
            return refreshLoaded(seriesTmdbId, found.value(), today, current);
        });
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
        boolean completeLoad = loadedSeasons.size() == seasonNumbers.size()
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
        return snapshot != null
                && snapshot.series().refreshedAt() != null
                && snapshot.series().refreshedAt().toLocalDate().equals(today)
                && snapshot.series().runtimeVerifiedAt() != null;
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
