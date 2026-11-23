package com.watchwise.watchwise_api.summary.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.feed.dto.FeedEventType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record HomeSocialActivityDTO(
        FeedEventType eventType,
        UUID id,
        UserPreviewDTO user,
        ContentRefDTO content,
        String targetLabel,
        Integer likesCount,
        Integer commentsCount,
        LocalDateTime createdAt,
        ContentCardDTO card,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer score,
        @JsonInclude(JsonInclude.Include.NON_NULL) String comment,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean likedByMe
) {

    public HomeSocialActivityDTO(FeedEventType eventType, UUID id, UserPreviewDTO user, ContentRefDTO content,
            String targetLabel, Integer likesCount, Integer commentsCount, LocalDateTime createdAt,
            ContentCardDTO card) {
        this(eventType, id, user, content, targetLabel, likesCount, commentsCount, createdAt, card, null, null, null);
    }

    public HomeSocialActivityDTO(FeedEventType eventType, UUID id, UserPreviewDTO user, ContentRefDTO content,
            String targetLabel, Integer likesCount, Integer commentsCount, LocalDateTime createdAt) {
        this(eventType, id, user, content, targetLabel, likesCount, commentsCount, createdAt, null);
    }
}
