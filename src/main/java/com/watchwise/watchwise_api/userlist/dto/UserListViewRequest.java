package com.watchwise.watchwise_api.userlist.dto;

import java.util.UUID;

public record UserListViewRequest(UUID viewerId, UUID ownerId, UserListViewQueryMode mode,
        UserListViewSource source, String query, UserListViewItemType itemType, String genre,
        String sortBy, String sortDirection, Integer page, Integer size) {
}
