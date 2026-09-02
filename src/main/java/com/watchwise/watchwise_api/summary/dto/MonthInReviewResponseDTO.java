package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;

import java.time.LocalDate;
import java.util.List;

public record MonthInReviewResponseDTO(
        List<DiaryEntryResponseDTO> recentWatched,
        List<DiaryEntryResponseDTO> topRated,
        List<DiaryEntryResponseDTO> bottomRated,
        List<RatingCountDTO> ratingsDistribution,
        long watchCount,
        long minutesWatched,
        LocalDate firstWatchedDate,
        LocalDate lastWatchedDate,
        List<DailyMinutesDTO> minutesPerDay,
        List<DayOfWeekCountDTO> watchCountByDayOfWeek,
        List<GenreCountDTO> genreCounts,
        List<SeriesWatchTimeDTO> topSeriesByWatchTime,
        List<ContentRefDTO> topLongestMovies,
        List<WatchCompanionCountDTO> topWatchCompanions) {
}
