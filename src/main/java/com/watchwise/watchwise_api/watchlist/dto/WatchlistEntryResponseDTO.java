package com.watchwise.watchwise_api.watchlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

public record WatchlistEntryResponseDTO(
        UUID id,
        ContentType type,
        ContentRefDTO content,
        Integer position,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDate releaseDate,
        Integer numberOfEpisodes,
        Double tmdbVoteAverage,
        Double communityAverageScore
) {
    public WatchlistEntryResponseDTO(
            UUID id,
            ContentType type,
            ContentRefDTO content,
            Integer position,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDate releaseDate) {
        this(id, type, content, position, createdAt, updatedAt, releaseDate, null, null, null);
    }

    public WatchlistEntryResponseDTO(
            UUID id,
            ContentType type,
            ContentRefDTO content,
            Integer position,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(id, type, content, position, createdAt, updatedAt, null, null, null, null);
    }
}
