package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DiaryViewReviewPreviewDTO(
        UUID id,
        ReviewPublicationKind publicationKind,
        ReviewContextKind contextKind,
        String comment,
        Integer scoreAtPublication,
        Integer watchNumber,
        Instant sourcePublishedAt,
        LocalDate sourcePublishedOn,
        Instant createdAt) {
}
