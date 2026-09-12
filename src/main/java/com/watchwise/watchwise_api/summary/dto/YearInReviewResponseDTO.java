package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;

import java.util.List;

public record YearInReviewResponseDTO(
        List<RatingCountDTO> ratingsDistribution,
        long watchCount,
        long minutesWatched,
        double averageMinutesPerMonth,
        double averageMinutesPerWeek,
        double averageMinutesPerDay,
        List<MonthCountDTO> watchCountByMonth,
        List<DayOfWeekCountDTO> watchCountByDayOfWeek,
        DiaryEntryResponseDTO firstWatched,
        DiaryEntryResponseDTO lastWatched,
        List<LongestWatchedItemDTO> longestWatched,
        List<GenreCountDTO> genreCounts,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<WatchCompanionCountDTO> topWatchCompanions) {
}
