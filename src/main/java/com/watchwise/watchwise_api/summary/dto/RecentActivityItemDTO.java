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
        ContentCardDTO card) {

    public RecentActivityItemDTO(ContentRefDTO content, RecentActivityStatus status, Integer score,
            Long timesWatched, String comment, LocalDateTime activityDate) {
        this(null, null, content, status, score, timesWatched, comment, activityDate, null);
    }

    public RecentActivityItemDTO(ContentRefDTO content, RecentActivityStatus status, Integer score,
            Long timesWatched, String comment, LocalDateTime activityDate, ContentCardDTO card) {
        this(null, null, content, status, score, timesWatched, comment, activityDate, card);
    }

    public RecentActivityItemDTO(ContentRefDTO content, RecentActivityStatus status,
            String comment, LocalDateTime activityDate) {
        this(null, null, content, status, null, null, comment, activityDate, null);
    }
}
