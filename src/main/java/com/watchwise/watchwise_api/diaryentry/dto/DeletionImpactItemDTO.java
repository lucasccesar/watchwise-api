package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;

public record DeletionImpactItemDTO(
        ContentType type,
        LocalDate watchedDate,
        Integer watchNumber,
        boolean hasReview
) {
}
