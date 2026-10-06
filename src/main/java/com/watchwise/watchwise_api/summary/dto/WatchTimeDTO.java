package com.watchwise.watchwise_api.summary.dto;

public record WatchTimeDTO(
        long totalMinutesWatched,
        long minutesWatchedLast30Days,
        long totalWatchedCount,
        long watchedCountLast30Days) {

    public WatchTimeDTO(long totalMinutesWatched, long minutesWatchedLast30Days) {
        this(totalMinutesWatched, minutesWatchedLast30Days, 0L, 0L);
    }
}
