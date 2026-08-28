package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

import java.time.LocalDateTime;

public record RecentActivityItemDTO(
        ContentRefDTO content,
        RecentActivityStatus status,
        String comment,
        LocalDateTime activityDate) {
}
