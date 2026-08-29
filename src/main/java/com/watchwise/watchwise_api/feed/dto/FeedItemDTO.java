package com.watchwise.watchwise_api.feed.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record FeedItemDTO(
        FeedEventType eventType,
        UUID id,
        UserPreviewDTO user,
        ContentRefDTO content,
        ContentType top5Type,
        Integer score,
        String comment,
        Integer likesCount,
        Boolean likedByMe,
        List<UserPreviewDTO> watchedWith,
        LocalDateTime createdAt
) {
}
