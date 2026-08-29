package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserListItemResponseDTO(
        UUID id,
        ContentRefDTO content,
        UserListPreviewDTO childList,
        Integer position,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl
) {
    public UserListItemResponseDTO(UUID id, ContentRefDTO content, UserListPreviewDTO childList, Integer position,
            String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, content, childList, position, description, createdAt, updatedAt, null);
    }
}