package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListDetailedResponseDTO(
        UUID id,
        String name,
        String description,
        UserListVisibility visibility,
        Double watchedPercentage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<UserListItemResponseDTO> items
) {
}
