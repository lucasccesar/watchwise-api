package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.summary.dto.SeriesInProgressPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Component
public class HomeNextEpisodeAssembler {

    private final TmdbClient tmdbClient;
    private final ContentRepository contentRepository;
    private final ContentStatsService contentStatsService;
    private final ExecutorService executor;

    public HomeNextEpisodeAssembler(
            TmdbClient tmdbClient,
            ContentRepository contentRepository,
            ContentStatsService contentStatsService,
            @Qualifier("homeNextEpisodeExecutor") ExecutorService executor) {
        this.tmdbClient = tmdbClient;
        this.contentRepository = contentRepository;
        this.contentStatsService = contentStatsService;
        this.executor = executor;
    }

    public List<SeriesInProgressPreviewDTO> assemble(User user, List<SeriesInProgressResponseDTO> progress) {
        return progress.stream()
                .map(item -> CompletableFuture.supplyAsync(() -> assembleOne(user, item), executor))
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
    }

    private java.util.Optional<SeriesInProgressPreviewDTO> assembleOne(
            User user, SeriesInProgressResponseDTO progress) {
        try {
            int seasonNumber = progress.maxSeasonNumber() == null ? 0 : progress.maxSeasonNumber();
            int episodeNumber = progress.maxEpisodeNumber() == null ? 0 : progress.maxEpisodeNumber() + 1;
            TmdbEpisodeFullDetails episode = lookupEpisode(progress.seriesTmdbId(), seasonNumber, episodeNumber, user.getPreferredLanguage());
            if (episode == null) {
                seasonNumber++;
                episodeNumber = 1;
                episode = lookupEpisode(progress.seriesTmdbId(), seasonNumber, episodeNumber, user.getPreferredLanguage());
            }
            if (episode == null) {
                return java.util.Optional.empty();
            }
            String seriesTitle = lookupSeriesTitle(progress.seriesTmdbId(), user.getPreferredLanguage());

            Content content = contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                    progress.seriesTmdbId(), seasonNumber, episodeNumber, ContentType.EPISODE).orElse(null);
            Double communityAverageScore = null;
            java.util.UUID contentId = null;
            if (content != null) {
                contentId = content.getId();
                ContentStatsResponseDTO stats = contentStatsService.getStats(contentId);
                communityAverageScore = stats.averageScore();
            }

            LocalDate releaseDate = parseDate(episode.airDate());
            return java.util.Optional.of(new SeriesInProgressPreviewDTO(
                    progress.seriesTmdbId(), seasonNumber, episodeNumber, progress.lastWatchedDate(),
                    progress.watchedEpisodeCount(), progress.totalEpisodeCount(), progress.watchedPercentage(),
                    seriesTitle, episode.name(), episode.stillPath(), episode.runtime(), releaseDate, contentId,
                    communityAverageScore, releaseDate == null || !releaseDate.isAfter(LocalDate.now())));
        } catch (TmdbUnavailableException exception) {
            return java.util.Optional.empty();
        }
    }

    private String lookupSeriesTitle(String seriesTmdbId, String language) {
        return switch (tmdbClient.getTvFullDetails(seriesTmdbId, language)) {
            case TmdbLookupResult.Found<TmdbTvFullDetails> found -> found.value().name();
            case TmdbLookupResult.NotFound<TmdbTvFullDetails> ignored -> null;
            case TmdbLookupResult.Unavailable<TmdbTvFullDetails> ignored -> throw new TmdbUnavailableException(
                    "TMDB is unavailable while resolving Home series titles");
        };
    }

    private TmdbEpisodeFullDetails lookupEpisode(String seriesTmdbId, int seasonNumber, int episodeNumber, String language) {
        if (seasonNumber <= 0 || episodeNumber <= 0) {
            return null;
        }
        return switch (tmdbClient.getEpisodeFullDetails(seriesTmdbId, seasonNumber, episodeNumber, language)) {
            case TmdbLookupResult.Found<TmdbEpisodeFullDetails> found -> found.value();
            case TmdbLookupResult.NotFound<TmdbEpisodeFullDetails> ignored -> null;
            case TmdbLookupResult.Unavailable<TmdbEpisodeFullDetails> ignored -> throw new TmdbUnavailableException(
                    "TMDB is unavailable while resolving Home next episodes");
        };
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
