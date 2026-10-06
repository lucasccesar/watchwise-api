package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.util.ArrayList;
import java.util.List;

public record SummaryResponseDTO(
        WatchTimeDTO watchTime,
        ProfileHighlightsDTO highlights,
        List<GenreCountDTO> genreCounts,
        RatingsSummaryDTO ratingsSummary,
        List<RatingCountDTO> ratingsDistribution,
        List<ProfileDiaryPreviewDTO> recentEpisodes,
        List<ProfileDiaryPreviewDTO> recentReviews,
        List<RecentActivityItemDTO> recentActivity) {

    public SummaryResponseDTO(
            WatchTimeDTO watchTime,
            List<GenreCountDTO> genreCounts,
            List<RatingCountDTO> ratingsDistribution,
            List<?> recentEpisodes,
            List<?> recentReviews,
            List<RecentActivityItemDTO> recentActivity) {
        this(watchTime, null, genreCounts, null, ratingsDistribution,
                toPreviews(recentEpisodes), toPreviews(recentReviews), recentActivity);
    }

    private static List<ProfileDiaryPreviewDTO> toPreviews(List<?> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ProfileDiaryPreviewDTO> previews = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (entry instanceof ProfileDiaryPreviewDTO preview) {
                previews.add(preview);
            } else if (entry instanceof DiaryEntryResponseDTO diaryEntry) {
                previews.add(new ProfileDiaryPreviewDTO(
                        diaryEntry.id(), diaryEntry.content(), diaryEntry.score(), diaryEntry.watchedDate(),
                        diaryEntry.watchNumber(), diaryEntry.customPosterUrl(),
                        diaryEntry.watchedWith() == null ? List.<UserPreviewDTO>of() : diaryEntry.watchedWith()));
            } else {
                throw new IllegalArgumentException("Unsupported profile diary preview type: "
                        + entry.getClass().getName());
            }
        }
        return List.copyOf(previews);
    }
}
