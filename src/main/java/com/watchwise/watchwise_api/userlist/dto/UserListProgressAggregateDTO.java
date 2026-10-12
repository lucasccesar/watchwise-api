package com.watchwise.watchwise_api.userlist.dto;

public record UserListProgressAggregateDTO(
        long totalItems,
        long watchedItems,
        long remainingItems,
        long totalRuntimeMinutes,
        long watchedRuntimeMinutes,
        long remainingRuntimeMinutes) {
}
