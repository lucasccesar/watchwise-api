package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.UUID;

public record UserListItemPageRequest(
        UUID viewerId,
        UUID listId,
        ContentType type,
        String genre,
        String sortBy,
        String sortDirection,
        WatchStatus watchStatus,
        Integer page,
        Integer size
) {
    public UserListItemPageRequest {
        genre = genre == null || genre.isBlank() ? null : genre.trim();
    }
}
