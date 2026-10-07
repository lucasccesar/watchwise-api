package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.UUID;

public record DiaryEntrySearchCriteria(
        UUID userId,
        ContentType type,
        LocalDate dateFrom,
        LocalDate dateTo,
        Boolean hasReview,
        String seriesTmdbId,
        Integer score,
        Integer scoreFrom,
        Integer scoreTo
) {
}
