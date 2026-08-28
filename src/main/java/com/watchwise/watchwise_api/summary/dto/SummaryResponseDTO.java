package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;

import java.util.List;

public record SummaryResponseDTO(
        WatchTimeDTO watchTime,
        List<GenreCountDTO> genreCounts,
        List<RatingCountDTO> ratingsDistribution,
        List<DiaryEntryResponseDTO> recentEpisodes,
        List<DiaryEntryResponseDTO> recentReviews,
        List<RecentActivityItemDTO> recentActivity) {
}
