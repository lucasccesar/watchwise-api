package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserListCardDTO(
        UUID id,
        UserPreviewDTO user,
        String name,
        String description,
        UserListVisibility visibility,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ContentCardDTO> previewCards,
        List<UserListPreviewDTO> nestedListPreviews,
        long nestedListsCount,
        long likesCount,
        boolean likedByMe,
        long itemsCount,
        long commentsCount,
        Integer rank,
        UserListItemScope itemScope,
        UserListDisplayScope displayScope,
        boolean ownedByMe
) {
    public UserListCardDTO {
        previewCards = previewCards == null ? List.of() : List.copyOf(previewCards);
        nestedListPreviews = nestedListPreviews == null ? List.of() : List.copyOf(nestedListPreviews);
    }

    public UserListCardDTO(UUID id, UserPreviewDTO user, String name, String description,
            UserListVisibility visibility, LocalDateTime createdAt, LocalDateTime updatedAt,
            List<ContentCardDTO> previewCards, List<UserListPreviewDTO> nestedListPreviews,
            long nestedListsCount, long likesCount, boolean likedByMe, long itemsCount,
            long commentsCount, Integer rank, UserListItemScope itemScope, boolean ownedByMe) {
        this(id, user, name, description, visibility, createdAt, updatedAt, previewCards,
                nestedListPreviews, nestedListsCount, likesCount, likedByMe, itemsCount,
                commentsCount, rank, itemScope, null, ownedByMe);
    }
}
