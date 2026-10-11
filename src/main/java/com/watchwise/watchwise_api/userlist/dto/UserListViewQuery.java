package com.watchwise.watchwise_api.userlist.dto;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import java.util.UUID;

public record UserListViewQuery(UUID viewerId, UUID ownerId, UserListViewQueryMode mode,
        UserListViewSource source, String query, UserListViewItemType itemType, String genre,
        UserListViewSort sortBy, Sort.Direction direction, Pageable pageable) {
}
