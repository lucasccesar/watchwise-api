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
        Integer episodeNumber,
        String title,
        String posterPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        ContentStatsResponseDTO stats,
        ContentViewerStateDTO viewerState) {
}
