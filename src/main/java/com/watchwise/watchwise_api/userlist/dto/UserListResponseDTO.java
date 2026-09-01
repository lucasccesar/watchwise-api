package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListResponseDTO(
        UUID id,
        String name,
        String description,
        UserListVisibility visibility,
        Double watchedPercentage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ContentRefDTO> previewItems,
        long nestedListsCount,
        Integer likesCount,
        Boolean likedByMe,
        long itemsCount,
        long commentsCount,
        long totalRuntimeMinutes,
        Integer rank,
        UserListItemScope itemScope
) {
}