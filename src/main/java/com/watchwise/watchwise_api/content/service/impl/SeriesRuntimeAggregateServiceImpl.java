package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeAggregate;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeAggregateService;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeResolution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
public class SeriesRuntimeAggregateServiceImpl implements SeriesRuntimeAggregateService {

    private static final long BASELINE_TTL_HOURS = 168;
    private final TmdbClient tmdbClient;
    private final ContentRepository contentRepository;
    private final SeriesRuntimeCalculator calculator;
    private final ExecutorService tmdbSeasonFetchExecutor;
    private final NewTransactionExecutor newTransactionExecutor;
    private final ConcurrentHashMap<java.util.UUID, Object> resolutionLocks = new ConcurrentHashMap<>();

    @Override
    public SeriesRuntimeResolution resolve(Content content, TmdbTvFullDetails freshDetails, String language) {
        UUID contentId = requirePersistedContentId(content);
        SeriesRuntimeAggregate reusable = reusableBaseline(content, freshDetails.seasons());
        if (reusable != null) {
            return new SeriesRuntimeResolution(reusable, List.of());
        }
        Object lock = resolutionLocks.computeIfAbsent(contentId, ignored -> new Object());
        synchronized (lock) {
            try {
                Content current = contentRepository.findById(contentId).orElse(content);
                SeriesRuntimeAggregate afterWait = reusableBaseline(current, freshDetails.seasons());
                if (afterWait != null) {
                    return new SeriesRuntimeResolution(afterWait, List.of());
                }
                return reconcile(current, freshDetails.seasons(), language);
            } finally {
                resolutionLocks.remove(contentId, lock);
            }
        }
    }

    @Override
    public void initializeIfMissing(Content content, TmdbTvDetails freshDetails) {
        UUID contentId = requirePersistedContentId(content);
        Object lock = resolutionLocks.computeIfAbsent(contentId, ignored -> new Object());
        synchronized (lock) {
            try {
                Content current = contentRepository.findById(contentId).orElse(content);
                if (!hasBaseline(current)) {
                    reconcile(current, freshDetails.seasons(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
                }
            } finally {
                resolutionLocks.remove(contentId, lock);
            }
        }
    }

    @Override
    public void incrementForNewEpisode(Content content, Integer seasonNumber, Integer episodeNumber,
            Integer reportedEpisodeCount) {
        Optional<TmdbEpisodeFullDetails> episode = tmdbClient.getEpisodeFullDetails(
                content.getTmdbId(), seasonNumber, episodeNumber, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE).toOptional();
        if (episode.isEmpty() || episode.get().runtime() == null || content.getId() == null || reportedEpisodeCount == null) {
            return;
        }
        newTransactionExecutor.runInNewTransaction(() -> {
            contentRepository.findByIdForUpdate(content.getId()).ifPresent(locked -> {
                if (!hasBaseline(locked)) {
                    return;
                }
                if (locked.getRuntimeReportedEpisodeCount() >= reportedEpisodeCount) {
                    return;
                }
                int total = locked.getTotalRuntimeMinutes() + episode.get().runtime();
                int count = locked.getRuntimeMinutesEpisodeCount() + 1;
                locked.setTotalRuntimeMinutes(total);
                locked.setRuntimeMinutesEpisodeCount(count);
                locked.setRuntimeMinutes((int) Math.round(total / (double) count));
                if (reportedEpisodeCount != null) {
                    locked.setRuntimeReportedEpisodeCount(reportedEpisodeCount);
                }
                locked.setUpdatedAt(LocalDateTime.now());
                contentRepository.save(locked);
            });
            return null;
        });
    }

    @Override
    public void reconcileBeforeFreezing(Content content, TmdbTvDetails freshDetails) {
        reconcile(content, freshDetails.seasons(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    private SeriesRuntimeResolution reconcile(Content content, List<TmdbSeasonSummary> summaries, String language) {
        List<TmdbSeasonSummary> regular = regularSeasons(summaries);
        List<TmdbSeasonFullDetails> fetched = regular.stream()
                .map(summary -> CompletableFuture.supplyAsync(
                        () -> tmdbClient.getSeasonFullDetails(content.getTmdbId(), summary.seasonNumber(), language).toOptional(),
                        tmdbSeasonFetchExecutor))
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
        if (fetched.size() != regular.size()) {
            return new SeriesRuntimeResolution(hasBaseline(content) ? storedAggregate(content) : nullAggregate(summaries), fetched);
        }
        SeriesRuntimeAggregate aggregate = calculator.calculate(fetched, summaries);
        if (aggregate.totalRuntimeMinutes() == null) {
            return new SeriesRuntimeResolution(hasBaseline(content) ? storedAggregate(content) : nullAggregate(summaries), fetched);
        }
        publishReconciledAggregate(content, aggregate);
        return new SeriesRuntimeResolution(aggregate, fetched);
    }

    private SeriesRuntimeAggregate reusableBaseline(Content content, List<TmdbSeasonSummary> summaries) {
        if (!hasBaseline(content) || content.getRuntimeAggregateVerifiedAt().isBefore(LocalDateTime.now().minusHours(BASELINE_TTL_HOURS))) {
            return null;
        }
        Integer reported = calculator.calculate(List.of(), summaries).reportedEpisodeCount();
        return Objects.equals(reported, content.getRuntimeReportedEpisodeCount()) ? storedAggregate(content) : null;
    }

    private boolean hasBaseline(Content content) {
        return content.getTotalRuntimeMinutes() != null && content.getRuntimeMinutesEpisodeCount() != null
                && content.getRuntimeReportedEpisodeCount() != null && content.getRuntimeAggregateVerifiedAt() != null;
    }

    private SeriesRuntimeAggregate storedAggregate(Content content) {
        Integer average = content.getRuntimeMinutes();
        if (average == null && content.getTotalRuntimeMinutes() != null
                && content.getRuntimeMinutesEpisodeCount() != null && content.getRuntimeMinutesEpisodeCount() > 0) {
            average = (int) Math.round(content.getTotalRuntimeMinutes() / (double) content.getRuntimeMinutesEpisodeCount());
        }
        return new SeriesRuntimeAggregate(content.getTotalRuntimeMinutes(), average,
                content.getRuntimeMinutesEpisodeCount(), content.getRuntimeReportedEpisodeCount());
    }

    private List<TmdbSeasonSummary> regularSeasons(List<TmdbSeasonSummary> summaries) {
        return summaries == null ? List.of() : summaries.stream()
                .filter(Objects::nonNull).filter(summary -> summary.seasonNumber() != null && summary.seasonNumber() != 0).toList();
    }

    private void publishReconciledAggregate(Content content, SeriesRuntimeAggregate aggregate) {
        UUID contentId = requirePersistedContentId(content);
        newTransactionExecutor.runInNewTransaction(() -> {
            Content locked = contentRepository.findByIdForUpdate(contentId).orElse(content);
            locked.setTotalRuntimeMinutes(aggregate.totalRuntimeMinutes());
            locked.setRuntimeMinutes(aggregate.averageRuntimeMinutes());
            locked.setRuntimeMinutesEpisodeCount(aggregate.knownEpisodeCount());
            locked.setRuntimeReportedEpisodeCount(aggregate.reportedEpisodeCount());
            locked.setRuntimeAggregateVerifiedAt(LocalDateTime.now());
            locked.setUpdatedAt(LocalDateTime.now());
            contentRepository.save(locked);
            return null;
        });
    }

    private SeriesRuntimeAggregate nullAggregate(List<TmdbSeasonSummary> summaries) {
        return new SeriesRuntimeAggregate(null, null, null, calculator.calculate(List.of(), summaries).reportedEpisodeCount());
    }

    private UUID requirePersistedContentId(Content content) {
        if (content == null || content.getId() == null) {
            throw new IllegalArgumentException("Series runtime aggregate requires persisted content");
        }
        return content.getId();
    }
}
