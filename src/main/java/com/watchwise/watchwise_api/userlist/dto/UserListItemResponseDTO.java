package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;

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
        String customPosterUrl,
        ContentStateDTO contentState
) {
    public UserListItemResponseDTO(UUID id, ContentRefDTO content, UserListPreviewDTO childList, Integer position,
            String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, content, childList, position, description, createdAt, updatedAt, null, null);
    }

    public UserListItemResponseDTO(UUID id, ContentRefDTO content, UserListPreviewDTO childList, Integer position,
            String description, LocalDateTime createdAt, LocalDateTime updatedAt, String customPosterUrl) {
        this(id, content, childList, position, description, createdAt, updatedAt, customPosterUrl, null);
    }
}
