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
        LocalDate releaseDate
) {
    public WatchlistEntryResponseDTO(
            UUID id,
            ContentType type,
            ContentRefDTO content,
            Integer position,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(id, type, content, position, createdAt, updatedAt, null);
    }
}
