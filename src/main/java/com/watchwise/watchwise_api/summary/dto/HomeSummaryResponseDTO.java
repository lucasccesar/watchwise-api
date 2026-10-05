package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import java.util.List;

public record HomeSummaryResponseDTO(
        long totalMinutesWatchedMovies,
        long totalMinutesWatchedEpisodes,
        long totalMoviesWatched,
        long totalDistinctMoviesWatched,
        long totalEpisodesWatched,
        List<SeriesInProgressPreviewDTO> nextEpisodes,
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
                totalEpisodesWatched, nextEpisodes, watchCountByDayLast30Days, genreCountsMoviesLast30Days,
                genreCountsSeriesLast30Days, null, false, List.of(), List.of());
    }
}
