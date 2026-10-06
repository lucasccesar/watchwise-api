package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import com.watchwise.watchwise_api.summary.dto.SeriesInProgressPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class HomeNextEpisodeAssembler {

    private final ContentRepository contentRepository;
    private final ContentStatsService contentStatsService;

    public HomeNextEpisodeAssembler(
            ContentRepository contentRepository,
            ContentStatsService contentStatsService) {
        this.contentRepository = contentRepository;
        this.contentStatsService = contentStatsService;
    }

    public List<SeriesInProgressPreviewDTO> assemble(User user, List<SeriesInProgressResponseDTO> progress) {
        return progress.stream()
                .map(this::assembleOne)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<SeriesInProgressPreviewDTO> assembleOne(SeriesInProgressResponseDTO progress) {
        ProgressEpisodeDTO episode = progress.nextEpisode();
        if (episode == null) {
            return Optional.empty();
        }

        Content content = contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                progress.seriesTmdbId(), episode.seasonNumber(), episode.episodeNumber(), ContentType.EPISODE)
                .orElse(null);
        Double communityAverageScore = null;
        java.util.UUID contentId = null;
        if (content != null) {
            contentId = content.getId();
            ContentStatsResponseDTO stats = contentStatsService.getStats(contentId);
            communityAverageScore = stats.averageScore();
        }

        return Optional.of(new SeriesInProgressPreviewDTO(
                progress.seriesTmdbId(), episode.seasonNumber(), episode.episodeNumber(), progress.lastWatchedDate(),
                progress.watchedEpisodeCount(), progress.totalEpisodeCount(), progress.watchedPercentage(),
                progress.seriesTitle(), episode.title(), episode.stillPath(), episode.runtimeMinutes(),
                episode.releaseDate(), contentId, communityAverageScore, episode.availableToWatch()));
    }
}
