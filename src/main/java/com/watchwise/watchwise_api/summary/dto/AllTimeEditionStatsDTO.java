package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;

import java.util.List;

public record AllTimeEditionStatsDTO(
        ContentType type,
        long watchedCount,
        long minutesWatched,
        long totalTheaterVisits,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<YearCountDTO> watchCountByYear,
        List<DecadeCountDTO> watchCountByDecade,
        List<CountryCountDTO> watchCountByCountry,
        List<ContentWatchCountDTO> mostLoggedContent,
        List<GenreCountDTO> genreCounts,
        List<RatingCountDTO> ratingsDistribution,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<ContentRatingSummaryDTO> currentSeriesAndSeasonTopRated,
        List<ContentRatingSummaryDTO> currentSeriesAndSeasonBottomRated) {

    public AllTimeEditionStatsDTO(ContentType type, long watchedCount, long minutesWatched, long totalTheaterVisits,
            double averageMinutesPerMonth, double averageMinutesPerWeek, double averageMinutesPerDay,
            List<YearCountDTO> watchCountByYear, List<DecadeCountDTO> watchCountByDecade,
            List<CountryCountDTO> watchCountByCountry, List<ContentWatchCountDTO> mostLoggedContent,
            List<GenreCountDTO> genreCounts, List<RatingCountDTO> ratingsDistribution,
            List<DiaryEntryResponseDTO> topRated, List<DiaryEntryResponseDTO> bottomRated) {
        this(type, watchedCount, minutesWatched, totalTheaterVisits, averageMinutesPerMonth, averageMinutesPerWeek,
                averageMinutesPerDay, watchCountByYear, watchCountByDecade, watchCountByCountry, mostLoggedContent,
                genreCounts, ratingsDistribution, topRated, bottomRated, List.of(), List.of());
    }
}
