package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;

import java.util.List;

public record AllTimeStatsResponseDTO(
        long totalMoviesWatched,
        long totalEpisodesWatched,
        long totalMinutesWatched,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<YearCountDTO> watchCountByYearMovies,
        List<YearCountDTO> watchCountByYearEpisodes,
        List<DecadeCountDTO> watchCountByDecade,
        List<CountryCountDTO> watchCountByCountry,
        List<ContentWatchCountDTO> mostLoggedContent,
        List<GenreCountDTO> genreCountsMovies,
        List<GenreCountDTO> genreCountsEpisodes,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated) {
}
