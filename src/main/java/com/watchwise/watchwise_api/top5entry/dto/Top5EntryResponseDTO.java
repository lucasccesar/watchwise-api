package com.watchwise.watchwise_api.top5entry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDateTime;
import java.util.UUID;

public record Top5EntryResponseDTO(
        UUID id,
        ContentType type,
        ContentRefDTO content,
        Integer position,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl,
        Integer score
) {
    public Top5EntryResponseDTO withCustomPosterUrl(String customPosterUrl) {
        return new Top5EntryResponseDTO(id, type, content, position, createdAt, updatedAt, customPosterUrl, score);
    }

    public Top5EntryResponseDTO withScore(Integer score) {
        return new Top5EntryResponseDTO(id, type, content, position, createdAt, updatedAt, customPosterUrl, score);
    }

    public Top5EntryResponseDTO(UUID id, ContentType type, ContentRefDTO content, Integer position,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, type, content, position, createdAt, updatedAt, null, null);
    }

    public Top5EntryResponseDTO(UUID id, ContentType type, ContentRefDTO content, Integer position,
            LocalDateTime createdAt, LocalDateTime updatedAt, String customPosterUrl) {
        this(id, type, content, position, createdAt, updatedAt, customPosterUrl, null);
    }
}
