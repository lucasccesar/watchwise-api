package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public record ContentReviewResponseDTO(
        UUID id,
        ContentReviewSource source,
        UUID userId,
        UserPreviewDTO reviewer,
        ContentRefDTO content,
        String comment,
        Integer score,
        LocalDate watchedDate,
        Integer watchNumber,
        Boolean watchedInTheater,
        String customPosterUrl,
        Boolean ignore,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer likesCount,
        long commentsCount,
        Boolean likedByMe,
        List<UserPreviewDTO> watchedWith,
        ContentCardDTO card
) {
    public static ContentReviewResponseDTO fromDiary(DiaryEntryResponseDTO entry) {
        return fromDiary(entry, null);
    }

    public static ContentReviewResponseDTO fromDiary(DiaryEntryResponseDTO entry, UserPreviewDTO reviewer) {
        return new ContentReviewResponseDTO(
                entry.id(), ContentReviewSource.DIARY, entry.userId(), reviewer, entry.content(), entry.comment(),
                entry.score(), entry.watchedDate(), entry.watchNumber(), entry.watchedInTheater(),
                entry.customPosterUrl(), entry.ignore(), entry.createdAt(), entry.updatedAt(), entry.likesCount(),
                entry.commentsCount(), entry.likedByMe(), entry.watchedWith(), entry.card());
    }

    public static ContentReviewResponseDTO fromContentReview(
            ContentReview review, UserPreviewDTO reviewer, ContentRefDTO content,
            int likesCount, long commentsCount, boolean likedByMe) {
        return new ContentReviewResponseDTO(
                review.getId(), ContentReviewSource.CONTENT_REVIEW, review.getUserId(), reviewer, content,
                review.getComment(), review.getScoreAtPublication(), null, review.getWatchNumber(), null, null, false,
                review.getSourcePublishedAt() != null
                        ? LocalDateTime.ofInstant(review.getSourcePublishedAt(), ZoneOffset.UTC)
                        : review.getSourcePublishedOn() != null
                        ? review.getSourcePublishedOn().atStartOfDay()
                        : LocalDateTime.ofInstant(review.getCreatedAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(review.getUpdatedAt(), ZoneOffset.UTC),
                likesCount, commentsCount, likedByMe, List.of(), null);
    }

    public ContentReviewResponseDTO withCard(ContentCardDTO card) {
        return new ContentReviewResponseDTO(
                id, source, userId, reviewer, content, comment, score, watchedDate, watchNumber, watchedInTheater,
                customPosterUrl, ignore, createdAt, updatedAt, likesCount, commentsCount, likedByMe, watchedWith, card);
    }

    public ContentReviewResponseDTO(UUID id, ContentReviewSource source, UUID userId, ContentRefDTO content,
            String comment, Integer score, LocalDate watchedDate, Integer watchNumber, Boolean watchedInTheater,
            String customPosterUrl, Boolean ignore, LocalDateTime createdAt, LocalDateTime updatedAt,
            Integer likesCount, Boolean likedByMe, List<UserPreviewDTO> watchedWith) {
        this(id, source, userId, null, content, comment, score, watchedDate, watchNumber, watchedInTheater,
                customPosterUrl, ignore, createdAt, updatedAt, likesCount, 0L, likedByMe, watchedWith, null);
    }
}
