package com.watchwise.watchwise_api.watchlist.dto;

public record WatchlistAggregateDTO(
        long totalCount,
        long movieCount,
        long seriesCount,
        long totalRuntimeMinutes,
        long upcomingCount) {
}
