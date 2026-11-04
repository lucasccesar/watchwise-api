package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListDetailViewResponseDTO(
        UUID id,
        UserPreviewDTO owner,
        String name,
        String description,
        UserListVisibility visibility,
        Double watchedPercentage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer likesCount,
        Boolean likedByMe,
        long itemsCount,
        long commentsCount,
        long totalRuntimeMinutes,
        Integer rank,
        UserListItemScope itemScope,
        PageResponseDTO<UserListDetailItemDTO> items,
        String backdropPath
) {
    public UserListDetailViewResponseDTO(
            UUID id,
            UserPreviewDTO owner,
            String name,
            String description,
            UserListVisibility visibility,
            Double watchedPercentage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Integer likesCount,
            Boolean likedByMe,
            long itemsCount,
            long commentsCount,
            long totalRuntimeMinutes,
            Integer rank,
            UserListItemScope itemScope,
            PageResponseDTO<UserListDetailItemDTO> items) {
        this(id, owner, name, description, visibility, watchedPercentage, createdAt, updatedAt,
                likesCount, likedByMe, itemsCount, commentsCount, totalRuntimeMinutes, rank, itemScope,
                items, null);
    }

    public UserListDetailViewResponseDTO {
        items = new PageResponseDTO<>(
                List.copyOf(items.content()), items.page(), items.size(), items.totalElements(),
                items.totalPages(), items.hasNext());
    }
}
