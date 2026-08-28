package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ContentRefDTO(
        UUID id,
        String tmdbId,
        ContentType type,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        Boolean isSeasonFinale,
        Boolean isSeriesFinale,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer runtimeMinutes,
        List<String> genres,
        Integer releaseYear,
        List<String> countries
) {
    public ContentRefDTO(UUID id, String tmdbId, ContentType type, String seriesTmdbId, Integer seasonNumber,
            Integer episodeNumber, Boolean isSeasonFinale, Boolean isSeriesFinale, LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(id, tmdbId, type, seriesTmdbId, seasonNumber, episodeNumber, isSeasonFinale, isSeriesFinale,
                createdAt, updatedAt, null, null, null, null);
    }

    public ContentRefDTO(UUID id, String tmdbId, ContentType type, String seriesTmdbId, Integer seasonNumber,
            Integer episodeNumber, Boolean isSeasonFinale, Boolean isSeriesFinale, LocalDateTime createdAt,
            LocalDateTime updatedAt, Integer runtimeMinutes, List<String> genres) {
        this(id, tmdbId, type, seriesTmdbId, seasonNumber, episodeNumber, isSeasonFinale, isSeriesFinale,
                createdAt, updatedAt, runtimeMinutes, genres, null, null);
    }
}
