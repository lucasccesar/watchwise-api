package com.watchwise.watchwise_api.userlist.dto;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.util.UUID;

public record UserListViewQuery(UUID viewerId, UUID ownerId, UserListViewQueryMode mode,
        UserListViewSource source, String query, String rawQuery, UserListViewItemType itemType, String genre,
        UserListViewSort sortBy, Sort.Direction direction, Pageable pageable) {

    public UserListViewQuery(UUID viewerId, UUID ownerId, UserListViewQueryMode mode,
            UserListViewSource source, String query, UserListViewItemType itemType, String genre,
            UserListViewSort sortBy, Sort.Direction direction, Pageable pageable) {
        this(viewerId, ownerId, mode, source, query, query, itemType, genre, sortBy, direction, pageable);
    }
}
