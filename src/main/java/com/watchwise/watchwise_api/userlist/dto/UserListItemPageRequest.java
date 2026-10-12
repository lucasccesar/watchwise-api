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
        Integer size,
        UserListItemPageMode mode
) {
    public UserListItemPageRequest {
        genre = genre == null || genre.isBlank() ? null : genre.trim();
        mode = mode == null ? UserListItemPageMode.RAW : mode;
    }

    public UserListItemPageRequest(
            UUID viewerId, UUID listId, ContentType type, String genre, String sortBy, String sortDirection,
            WatchStatus watchStatus, Integer page, Integer size) {
        this(viewerId, listId, type, genre, sortBy, sortDirection, watchStatus, page, size,
                UserListItemPageMode.RAW);
    }
}
