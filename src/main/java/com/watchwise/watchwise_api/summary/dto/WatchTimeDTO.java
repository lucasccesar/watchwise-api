package com.watchwise.watchwise_api.summary.dto;

public record WatchTimeDTO(
        long totalMinutesWatched,
        long minutesWatchedLast30Days) {
}
