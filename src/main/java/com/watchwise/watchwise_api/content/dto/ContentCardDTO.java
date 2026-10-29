package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContentCardDTO(
        UUID contentId,
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String posterPath,
        String customPosterUrl,
        LocalDate releaseDate,
        Integer releaseYear,
        Double tmdbVoteAverage,
        Integer runtimeMinutes,
        Integer totalRuntimeMinutes,
        Integer numberOfSeasons,
        Integer numberOfEpisodes,
        List<String> genres,
        ContentCardStatsDTO stats,
        ContentCardViewerStateDTO viewerState,
        ContentPreviewStatus previewStatus) {

    public ContentCardDTO {
        genres = genres == null ? null : List.copyOf(genres);
    }

    public ContentCardDTO(
            UUID contentId,
            ContentType type,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            String title,
            String posterPath,
            String customPosterUrl,
            LocalDate releaseDate,
            Integer releaseYear,
            Integer runtimeMinutes,
            Integer totalRuntimeMinutes,
            Integer numberOfSeasons,
            Integer numberOfEpisodes,
            List<String> genres,
            ContentCardStatsDTO stats,
            ContentCardViewerStateDTO viewerState,
            ContentPreviewStatus previewStatus) {
        this(contentId, type, tmdbId, seriesTmdbId, seasonNumber, episodeNumber, title, posterPath,
                customPosterUrl, releaseDate, releaseYear, null, runtimeMinutes, totalRuntimeMinutes,
                numberOfSeasons, numberOfEpisodes, genres, stats, viewerState, previewStatus);
    }
}
