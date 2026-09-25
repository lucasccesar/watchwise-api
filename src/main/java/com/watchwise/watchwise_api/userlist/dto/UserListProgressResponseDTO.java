package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListProgressResponseDTO(
        UUID id,
        String name,
        String description,
        UserListVisibility visibility,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer likesCount,
        Boolean likedByMe,
        long itemsCount,
        long commentsCount,
        long totalRuntimeMinutes,
        Integer rank,
        UserListItemScope itemScope,
        long totalItems,
        long watchedItems,
        double watchedPercentage,
        List<UserListProgressItemDTO> items) {

    public UserListProgressResponseDTO {
        items = List.copyOf(items);
    }
}
