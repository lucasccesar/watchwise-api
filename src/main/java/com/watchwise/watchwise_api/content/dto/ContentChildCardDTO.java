package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.UUID;

public record ContentChildCardDTO(
        UUID contentId,
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeCount,
        Integer episodeNumber,
        String title,
        String overview,
        String posterPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        ContentStatsResponseDTO stats,
        ContentViewerStateDTO viewerState) {

    public ContentChildCardDTO(
            UUID contentId,
            ContentType type,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            String title,
            String overview,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            ContentStatsResponseDTO stats,
            ContentViewerStateDTO viewerState) {
        this(contentId, type, tmdbId, seriesTmdbId, seasonNumber, null, episodeNumber, title,
                overview, posterPath, releaseDate, runtimeMinutes, stats, viewerState);
    }

    public ContentChildCardDTO(
            UUID contentId,
            ContentType type,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            String title,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            ContentStatsResponseDTO stats,
            ContentViewerStateDTO viewerState) {
        this(contentId, type, tmdbId, seriesTmdbId, seasonNumber, null, episodeNumber, title, null,
                posterPath, releaseDate, runtimeMinutes, stats, viewerState);
    }
}
