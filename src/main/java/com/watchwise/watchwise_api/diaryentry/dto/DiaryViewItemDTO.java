package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DiaryViewItemDTO(
        Kind kind,
        UUID diaryEntryId,
        String completionKey,
        ContentRefDTO content,
        Integer watchNumber,
        UUID anchorEntryId,
        LocalDate watchedDate,
        Instant orderedAt,
        Integer currentScore,
        Long currentRatingVersion,
        List<ContentReviewResponseDTO> reviewPreviews,
        long reviewCount) {

    public DiaryViewItemDTO {
        reviewPreviews = reviewPreviews == null ? List.of() : List.copyOf(reviewPreviews);
    }

    public enum Kind {
        PERSISTED_ENTRY,
        DERIVED_COMPLETION
    }
}
