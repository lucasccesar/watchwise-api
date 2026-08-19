package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserListResponseDTO(
        UUID id,
        UserPreviewDTO user,
        String name,
        String description,
        Boolean isPublic,
        Double watchedPercentage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}