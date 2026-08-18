package com.watchwise.watchwise_api.dropped.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDateTime;
import java.util.UUID;

public record DroppedEntryResponseDTO(
        UUID id,
        ContentType type,
        ContentRefDTO content,
        String comment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}