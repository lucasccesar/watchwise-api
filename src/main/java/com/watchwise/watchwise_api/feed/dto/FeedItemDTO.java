package com.watchwise.watchwise_api.feed.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
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
        PickPreviewDTO pick,
        PicksTemplatePreviewDTO picksTemplate,
        LocalDateTime createdAt,
        List<Top5EntryResponseDTO> top5
) {

    public FeedItemDTO(FeedEventType eventType, UUID id, UserPreviewDTO user, ContentRefDTO content,
            ContentType top5Type, Integer score, String comment, Integer likesCount, Boolean likedByMe,
            List<UserPreviewDTO> watchedWith, PickPreviewDTO pick, PicksTemplatePreviewDTO picksTemplate,
            LocalDateTime createdAt) {
        this(eventType, id, user, content, top5Type, score, comment, likesCount, likedByMe, watchedWith,
                pick, picksTemplate, createdAt, null);
    }
}
