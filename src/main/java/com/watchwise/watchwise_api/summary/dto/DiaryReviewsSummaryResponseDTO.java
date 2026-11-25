package com.watchwise.watchwise_api.summary.dto;

public record DiaryReviewsSummaryResponseDTO(
        long totalMinutesWatchedMovies,
        long totalMinutesWatchedEpisodes,
        long totalMoviesWatched,
        long totalDistinctMoviesWatched,
        long totalEpisodesWatched,
        long distinctSeriesWatched,
        String latestWatchedBackdropPath
) {
}
