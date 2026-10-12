package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListNestedDetailDTO(
        UserPreviewDTO owner,
        UUID id,
        String name,
        String description,
        UserListVisibility visibility,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UserListItemScope itemScope,
        long itemsCount,
        Integer likesCount,
        long commentsCount,
        List<ContentCardDTO> previewCards
) {
    public UserListNestedDetailDTO {
        previewCards = List.copyOf(previewCards);
        if (previewCards.size() > 5) {
            throw new IllegalArgumentException("A nested list can contain at most five preview cards");
        }
    }
}
