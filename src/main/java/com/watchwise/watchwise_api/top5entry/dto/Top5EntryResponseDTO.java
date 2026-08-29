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
        String customPosterUrl
) {
    public Top5EntryResponseDTO(UUID id, ContentType type, ContentRefDTO content, Integer position,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, type, content, position, createdAt, updatedAt, null);
    }
}
