package com.watchwise.watchwise_api.feed.dto;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameResultPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record FeedItemViewDTO(
        FeedEventType eventType,
        UUID id,
        UserPreviewDTO user,
        ContentRefDTO content,
        ContentType top5Type,
        Integer score,
        String comment,
        Integer likesCount,
        Boolean likedByMe,
        Integer commentsCount,
        List<CommentResponseDTO> recentComments,
        List<UserPreviewDTO> watchedWith,
        PickPreviewDTO pick,
        PicksTemplatePreviewDTO picksTemplate,
        DailyGameResultPreviewDTO dailyGameResult,
        LocalDateTime createdAt,
        List<Top5EntryResponseDTO> top5,
        ContentCardDTO card,
        FeedTop5PreviewDTO top5Preview,
        List<ContentCardDTO> pickTargetCards) {

    public FeedItemViewDTO {
        recentComments = recentComments == null ? null : List.copyOf(recentComments);
        watchedWith = watchedWith == null ? null : List.copyOf(watchedWith);
        top5 = top5 == null ? null : List.copyOf(top5);
        pickTargetCards = pickTargetCards == null
                ? null : Collections.unmodifiableList(new ArrayList<>(pickTargetCards));
    }

    public FeedItemViewDTO(FeedItemDTO item, ContentCardDTO card, FeedTop5PreviewDTO top5Preview,
            List<ContentCardDTO> pickTargetCards) {
        this(item.eventType(), item.id(), item.user(), item.content(), item.top5Type(), item.score(), item.comment(),
                item.likesCount(), item.likedByMe(), item.commentsCount(), item.recentComments(), item.watchedWith(),
                item.pick(), item.picksTemplate(), item.dailyGameResult(), item.createdAt(), item.top5(), card,
                top5Preview, pickTargetCards);
    }
}
