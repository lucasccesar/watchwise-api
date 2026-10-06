package com.watchwise.watchwise_api.summary.service;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.summary.dto.ProfileDiaryPreviewDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileRewatchKind;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.RecentActivityItemDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.List;
import java.util.UUID;

public interface ProfileSummaryDataReader {

    Snapshot read(UUID userId, ContentType type);

    record Snapshot(
            WatchTimeDTO watchTime,
            List<GenreCountDTO> genreCounts,
            List<RatingCountDTO> ratingsDistribution,
            List<ProfileDiaryPreviewDTO> recentEpisodes,
            List<ProfileDiaryPreviewDTO> recentReviews,
            List<RecentActivityItemDTO> recentActivity,
            RewatchData rewatch,
            LongestWatchData longestWatch) {
    }

    record RewatchData(ProfileRewatchKind kind, Content content, long count) {
    }

    record LongestWatchData(Content content, String seriesTmdbId, long totalMinutesWatched,
            Long watchedEpisodeCount) {
    }
}
