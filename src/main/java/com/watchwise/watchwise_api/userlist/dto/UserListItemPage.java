package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.userlist.entity.UserList;

import java.util.List;

public record UserListItemPage(
        UserList userList,
        List<UserListItemResponseDTO> items,
        Double watchedPercentage,
        Integer likesCount,
        Boolean likedByMe,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long itemsCount,
        long commentsCount,
        long totalRuntimeMinutes,
        Integer rank,
        UserListItemScope itemScope
) {
    public UserListItemPage {
        items = List.copyOf(items);
    }
}
