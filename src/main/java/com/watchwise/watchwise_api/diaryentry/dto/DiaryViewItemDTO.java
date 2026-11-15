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
        List<DiaryViewReviewPreviewDTO> reviewPreviews,
        long reviewCount,
        List<UUID> relatedDiaryEntryIds) implements DiaryViewResponseItemDTO {

    public DiaryViewItemDTO {
        reviewPreviews = reviewPreviews == null ? List.of() : List.copyOf(reviewPreviews);
        relatedDiaryEntryIds = relatedDiaryEntryIds == null ? List.of() : List.copyOf(relatedDiaryEntryIds);
    }

    public DiaryViewItemDTO withRelatedDiaryEntryIds(List<UUID> entryIds) {
        return new DiaryViewItemDTO(kind, diaryEntryId, completionKey, content, watchNumber, anchorEntryId,
                watchedDate, orderedAt, currentScore, currentRatingVersion, reviewPreviews, reviewCount, entryIds);
    }

    public enum Kind {
        PERSISTED_ENTRY,
        DERIVED_COMPLETION
    }
}
