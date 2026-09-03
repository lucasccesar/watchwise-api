package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;

import java.util.List;

public record HomeSummaryResponseDTO(
        long totalMinutesWatched,
        long totalMoviesWatched,
        long totalEpisodesWatched,
        List<SeriesInProgressResponseDTO> nextEpisodes,
        List<DailyWatchCountDTO> watchCountByDayLast30Days,
        List<GenreCountDTO> genreCountsMoviesLast30Days,
        List<GenreCountDTO> genreCountsSeriesLast30Days,
        List<DiaryEntryResponseDTO> recentlyWatched
) {
}
