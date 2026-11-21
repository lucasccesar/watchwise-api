package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record RecentActivityItemDTO(
        UUID id,
        RecentActivitySource source,
        ContentRefDTO content,
        RecentActivityStatus status,
        Integer score,
        Long timesWatched,
        String comment,
        LocalDateTime activityDate,
        ContentCardDTO card,
        Long watchedEpisodeCount) {

    public RecentActivityItemDTO(UUID id, RecentActivitySource source, ContentRefDTO content,
            RecentActivityStatus status, Integer score, Long timesWatched, String comment,
            LocalDateTime activityDate, ContentCardDTO card) {
        this(id, source, content, status, score, timesWatched, comment, activityDate, card, null);
    }

    public RecentActivityItemDTO(ContentRefDTO content, RecentActivityStatus status, Integer score,
            Long timesWatched, String comment, LocalDateTime activityDate) {
        this(null, null, content, status, score, timesWatched, comment, activityDate, null, null);
    }

    public RecentActivityItemDTO(ContentRefDTO content, RecentActivityStatus status, Integer score,
            Long timesWatched, String comment, LocalDateTime activityDate, ContentCardDTO card) {
        this(null, null, content, status, score, timesWatched, comment, activityDate, card, null);
    }

    public RecentActivityItemDTO(ContentRefDTO content, RecentActivityStatus status,
            String comment, LocalDateTime activityDate) {
        this(null, null, content, status, null, null, comment, activityDate, null, null);
    }

    public RecentActivityItemDTO withWatchedEpisodeCount(Long count) {
        return new RecentActivityItemDTO(id, source, content, status, score, timesWatched, comment,
                activityDate, card, count);
    }

    public RecentActivityItemDTO withCard(ContentCardDTO value) {
        return new RecentActivityItemDTO(id, source, content, status, score, timesWatched, comment,
                activityDate, value, watchedEpisodeCount);
    }
}
