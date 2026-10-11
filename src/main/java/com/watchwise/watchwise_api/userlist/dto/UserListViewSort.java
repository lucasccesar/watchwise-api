package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

public enum UserListViewSort {
    NAME, LIKES_COUNT, ITEMS_COUNT, COMMENTS_COUNT, UPDATED_AT, RANK;

    public static UserListViewSort fromApiValue(String value) {
        if (value == null) {
            throw new BadRequestException("Unsupported user list sort value");
        }
        return switch (value) {
            case "name" -> NAME;
            case "likesCount" -> LIKES_COUNT;
            case "itemsCount" -> ITEMS_COUNT;
            case "commentsCount" -> COMMENTS_COUNT;
            case "updatedAt" -> UPDATED_AT;
            case "rank" -> RANK;
            default -> throw new BadRequestException("Unsupported user list sort value: " + value);
        };
    }
}
