package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record DiaryEntryResponseDTO(
        UUID id,
        UUID userId,
        ContentRefDTO content,
        String comment,
        Integer score,
        LocalDate watchedDate,
        Instant watchedAt,
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
    public DiaryEntryResponseDTO withCustomPosterUrl(String customPosterUrl) {
        return new DiaryEntryResponseDTO(
                id, userId, content, comment, score, watchedDate, watchedAt, watchNumber, watchedInTheater,
                customPosterUrl, ignore, createdAt, updatedAt, likesCount, commentsCount, likedByMe, watchedWith, card);
    }

    public DiaryEntryResponseDTO withCommentsCount(long commentsCount) {
        return new DiaryEntryResponseDTO(
                id, userId, content, comment, score, watchedDate, watchedAt, watchNumber, watchedInTheater,
                customPosterUrl, ignore, createdAt, updatedAt, likesCount, commentsCount, likedByMe, watchedWith, card);
    }

    public DiaryEntryResponseDTO withCard(ContentCardDTO card) {
        return new DiaryEntryResponseDTO(
                id, userId, content, comment, score, watchedDate, watchedAt, watchNumber, watchedInTheater,
                customPosterUrl, ignore, createdAt, updatedAt, likesCount, commentsCount, likedByMe, watchedWith, card);
    }

    public DiaryEntryResponseDTO(UUID id, UUID userId, ContentRefDTO content, String comment, Integer score,
            LocalDate watchedDate, Integer watchNumber, Boolean watchedInTheater, String customPosterUrl,
            Boolean ignore, LocalDateTime createdAt, LocalDateTime updatedAt, Integer likesCount,
            Boolean likedByMe) {
        this(id, userId, content, comment, score, watchedDate, null, watchNumber, watchedInTheater, customPosterUrl,
                ignore, createdAt, updatedAt, likesCount, 0L, likedByMe, List.of(), null);
    }

    public DiaryEntryResponseDTO(UUID id, UUID userId, ContentRefDTO content, String comment, Integer score,
            LocalDate watchedDate, Integer watchNumber, Boolean watchedInTheater, String customPosterUrl,
            Boolean ignore, LocalDateTime createdAt, LocalDateTime updatedAt, Integer likesCount,
            Boolean likedByMe, List<UserPreviewDTO> watchedWith) {
        this(id, userId, content, comment, score, watchedDate, null, watchNumber, watchedInTheater, customPosterUrl,
                ignore, createdAt, updatedAt, likesCount, 0L, likedByMe, watchedWith, null);
    }

    public DiaryEntryResponseDTO(UUID id, UUID userId, ContentRefDTO content, String comment, Integer score,
            LocalDate watchedDate, Integer watchNumber, Boolean watchedInTheater, String customPosterUrl,
            Boolean ignore, LocalDateTime createdAt, LocalDateTime updatedAt, Integer likesCount,
            long commentsCount, Boolean likedByMe, List<UserPreviewDTO> watchedWith, ContentCardDTO card) {
        this(id, userId, content, comment, score, watchedDate, null, watchNumber, watchedInTheater,
                customPosterUrl, ignore, createdAt, updatedAt, likesCount, commentsCount, likedByMe, watchedWith, card);
    }
}
