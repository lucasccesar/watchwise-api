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
    private final ConcurrentHashMap<UUID, LockEntry> resolutionLocks = new ConcurrentHashMap<>();

    @Override
    public SeriesRuntimeResolution resolve(Content content, TmdbTvFullDetails freshDetails, String language) {
        UUID contentId = requirePersistedContentId(content);
        SeriesRuntimeAggregate reusable = reusableBaseline(content, freshDetails.seasons());
        if (reusable != null) {
            return new SeriesRuntimeResolution(reusable, List.of());
        }
        LockEntry lock = acquireLock(contentId);
        synchronized (lock.monitor) {
            try {
                Content current = contentRepository.findById(contentId).orElse(content);
                SeriesRuntimeAggregate afterWait = reusableBaseline(current, freshDetails.seasons());
                if (afterWait != null) {
                    return new SeriesRuntimeResolution(afterWait, List.of());
                }
                return reconcile(current, freshDetails.seasons(), language);
            } finally { releaseLock(contentId, lock); }
        }
    }

    @Override
    public void initializeIfMissing(Content content, TmdbTvDetails freshDetails) {
        UUID contentId = requirePersistedContentId(content);
        LockEntry lock = acquireLock(contentId);
        synchronized (lock.monitor) {
            try {
                Content current = contentRepository.findById(contentId).orElse(content);
                if (!hasBaseline(current)) {
                    reconcile(current, freshDetails.seasons(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
                }
            } finally { releaseLock(contentId, lock); }
        }
    }

    @Override
    public void incrementForNewEpisode(Content content, Integer seasonNumber, Integer episodeNumber,
            Integer reportedEpisodeCount) {
        if (content.getId() == null || reportedEpisodeCount == null) {
            return;
        }
        Optional<TmdbEpisodeFullDetails> episode = tmdbClient.getEpisodeFullDetails(
                content.getTmdbId(), seasonNumber, episodeNumber, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE).toOptional();
        Integer episodeRuntime = episode.map(TmdbEpisodeFullDetails::runtime).orElse(null);
        boolean reconciliationNeeded = newTransactionExecutor.runInNewTransaction(() -> {
            Content locked = contentRepository.findByIdForUpdate(content.getId()).orElse(null);
            if (locked == null || !hasBaseline(locked) || locked.getRuntimeReportedEpisodeCount() >= reportedEpisodeCount) return false;
            if (reportedEpisodeCount != locked.getRuntimeReportedEpisodeCount() + 1) return true;
            if (episodeRuntime == null) return false;
            int total = locked.getTotalRuntimeMinutes() + episodeRuntime;
            int count = locked.getRuntimeMinutesEpisodeCount() + 1;
            locked.setTotalRuntimeMinutes(total);
            locked.setRuntimeMinutesEpisodeCount(count);
            locked.setRuntimeMinutes((int) Math.round(total / (double) count));
            locked.setRuntimeReportedEpisodeCount(reportedEpisodeCount);
            locked.setUpdatedAt(LocalDateTime.now());
            contentRepository.save(locked);
            return false;
        });
        if (reconciliationNeeded) tmdbClient.getTvDetails(content.getTmdbId()).ifPresent(details ->
                reconcile(content, details.seasons(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
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
            return new SeriesRuntimeResolution(hasBaseline(content) ? storedAggregate(content) : nullAggregate(summaries), fetched, true);
        }
        SeriesRuntimeAggregate aggregate = calculator.calculate(fetched, summaries);
        if (aggregate.totalRuntimeMinutes() == null) {
            return new SeriesRuntimeResolution(hasBaseline(content) ? storedAggregate(content) : nullAggregate(summaries), fetched, true);
        }
        SeriesRuntimeAggregate published = publishReconciledAggregate(content, aggregate);
        return new SeriesRuntimeResolution(published, fetched, true);
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

    private SeriesRuntimeAggregate publishReconciledAggregate(Content content, SeriesRuntimeAggregate aggregate) {
        UUID contentId = requirePersistedContentId(content);
        return newTransactionExecutor.runInNewTransaction(() -> {
            Content locked = contentRepository.findByIdForUpdate(contentId).orElse(content);
            if (locked.getRuntimeReportedEpisodeCount() != null && aggregate.reportedEpisodeCount() != null
                    && locked.getRuntimeReportedEpisodeCount() > aggregate.reportedEpisodeCount()) {
                return storedAggregate(locked);
            }
            locked.setTotalRuntimeMinutes(aggregate.totalRuntimeMinutes());
            locked.setRuntimeMinutes(aggregate.averageRuntimeMinutes());
            locked.setRuntimeMinutesEpisodeCount(aggregate.knownEpisodeCount());
            locked.setRuntimeReportedEpisodeCount(aggregate.reportedEpisodeCount());
            locked.setRuntimeAggregateVerifiedAt(LocalDateTime.now());
            locked.setUpdatedAt(LocalDateTime.now());
            contentRepository.save(locked);
            return aggregate;
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

    private LockEntry acquireLock(UUID contentId) {
        return resolutionLocks.compute(contentId, (ignored, entry) -> {
            LockEntry selected = entry == null ? new LockEntry() : entry;
            selected.participants++;
            return selected;
        });
    }

    private void releaseLock(UUID contentId, LockEntry entry) {
        resolutionLocks.computeIfPresent(contentId, (ignored, current) -> {
            if (current != entry) return current;
            return --current.participants == 0 ? null : current;
        });
    }

    private static final class LockEntry { private final Object monitor = new Object(); private int participants; }
}
