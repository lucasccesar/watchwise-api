package com.watchwise.watchwise_api.contentreview.dto;

import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ContentReviewViewDTO(
        UUID id,
        UUID contentId,
        String comment,
        Integer scoreAtPublication,
        Integer watchNumber,
        ReviewContextKind contextKind,
        Instant sourcePublishedAt,
        LocalDate sourcePublishedOn,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
