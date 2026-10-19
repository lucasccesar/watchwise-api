package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDisplayScope;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SearchListViewDTO(
        UUID id,
        UserPreviewDTO owner,
        String name,
        String description,
        UserListVisibility visibility,
        LocalDateTime updatedAt,
        List<SearchListPreviewCardDTO> previewCards,
        long itemsCount,
        UserListDisplayScope displayScope,
        long likesCount,
        long commentsCount,
        boolean likedByViewer,
        boolean ownedByViewer) {

    public SearchListViewDTO {
        previewCards = previewCards == null ? List.of() : List.copyOf(previewCards);
    }
}
