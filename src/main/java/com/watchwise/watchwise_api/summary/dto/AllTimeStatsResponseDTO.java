package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;

import java.util.List;

public record AllTimeStatsResponseDTO(
        long totalMoviesWatched,
        long totalEpisodesWatched,
        long totalMinutesWatchedMovies,
        long totalMinutesWatchedEpisodes,
        long totalTheaterVisits,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<YearCountDTO> watchCountByYearMovies,
        List<YearCountDTO> watchCountByYearEpisodes,
        List<DecadeCountDTO> watchCountByDecade,
        List<CountryCountDTO> watchCountByCountry,
        List<ContentWatchCountDTO> mostLoggedContent,
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsSeries,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<WatchCompanionCountDTO> topWatchCompanions,
        List<ContentRatingSummaryDTO> currentSeriesAndSeasonTopRated,
        List<ContentRatingSummaryDTO> currentSeriesAndSeasonBottomRated) {

    public AllTimeStatsResponseDTO(long totalMoviesWatched, long totalEpisodesWatched,
            long totalMinutesWatchedMovies, long totalMinutesWatchedEpisodes, long totalTheaterVisits,
            double averageMinutesPerMonth, double averageMinutesPerWeek, double averageMinutesPerDay,
            List<YearCountDTO> watchCountByYearMovies, List<YearCountDTO> watchCountByYearEpisodes,
            List<DecadeCountDTO> watchCountByDecade, List<CountryCountDTO> watchCountByCountry,
            List<ContentWatchCountDTO> mostLoggedContent, List<GenreCountDTO> genreCountsMovies,
            List<GenreCountDTO> genreCountsSeries, List<DiaryEntryResponseDTO> topRated,
            List<DiaryEntryResponseDTO> bottomRated, List<WatchCompanionCountDTO> topWatchCompanions) {
        this(totalMoviesWatched, totalEpisodesWatched, totalMinutesWatchedMovies, totalMinutesWatchedEpisodes,
                totalTheaterVisits, averageMinutesPerMonth, averageMinutesPerWeek, averageMinutesPerDay,
                watchCountByYearMovies, watchCountByYearEpisodes, watchCountByDecade, watchCountByCountry,
                mostLoggedContent, genreCountsMovies, genreCountsSeries, topRated, bottomRated,
                topWatchCompanions, List.of(), List.of());
    }
}
