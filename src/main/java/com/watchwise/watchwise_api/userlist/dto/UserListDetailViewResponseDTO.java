package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListDetailViewResponseDTO(
        UUID id,
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
        PageResponseDTO<UserListDetailItemDTO> items
) {
    public UserListDetailViewResponseDTO {
        items = new PageResponseDTO<>(
                List.copyOf(items.content()), items.page(), items.size(), items.totalElements(),
                items.totalPages(), items.hasNext());
    }
}
