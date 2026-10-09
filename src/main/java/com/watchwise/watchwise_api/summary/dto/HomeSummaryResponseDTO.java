package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventDTO;
import java.util.List;

public record HomeSummaryResponseDTO(
        long totalMinutesWatchedMovies,
        long totalMinutesWatchedEpisodes,
        long totalMoviesWatched,
        long totalDistinctMoviesWatched,
        long totalEpisodesWatched,
        long distinctSeriesWatched,
        List<SeriesInProgressPreviewDTO> nextEpisodes,
        List<CalendarEventDTO> upcomingReleases,
        List<DailyWatchCountDTO> watchCountByDayLast30Days,
        List<GenreCountDTO> genreCountsMoviesLast30Days,
        List<GenreCountDTO> genreCountsEpisodesLast30Days,
        HomeViewerDTO viewer,
        boolean hasUnreadNotifications,
        List<HomeRecentlyWatchedDTO> recentlyWatched,
        List<HomeSocialActivityDTO> socialActivities
) {
    public HomeSummaryResponseDTO(
            long totalMinutesWatchedMovies,
            long totalMinutesWatchedEpisodes,
        long totalMoviesWatched,
        long totalEpisodesWatched,
            List<SeriesInProgressPreviewDTO> nextEpisodes,
            List<DailyWatchCountDTO> watchCountByDayLast30Days,
            List<GenreCountDTO> genreCountsMoviesLast30Days,
            List<GenreCountDTO> genreCountsSeriesLast30Days,
            List<?> recentlyWatched
    ) {
        this(totalMinutesWatchedMovies, totalMinutesWatchedEpisodes, totalMoviesWatched, totalMoviesWatched,
                totalEpisodesWatched, 0L, nextEpisodes, List.of(), watchCountByDayLast30Days, genreCountsMoviesLast30Days,
                genreCountsSeriesLast30Days, null, false, toRecentlyWatched(recentlyWatched), List.of());
    }

    private static List<HomeRecentlyWatchedDTO> toRecentlyWatched(List<?> recentlyWatched) {
        if (recentlyWatched == null || recentlyWatched.isEmpty()) {
            return List.of();
        }
        return recentlyWatched.stream()
                .filter(HomeRecentlyWatchedDTO.class::isInstance)
                .map(HomeRecentlyWatchedDTO.class::cast)
                .toList();
    }
}
