package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntrySort;

import java.time.LocalDate;

public record DiaryViewFilter(
        ContentType type,
        LocalDate dateStart,
        LocalDate dateEnd,
        Integer scoreMin,
        Integer scoreMax,
        Boolean hasReview,
        String seriesTmdbId,
        Boolean watchedInTheater,
        DiaryEntrySort sortBy) {

    public DiaryViewFilter(ContentType type, LocalDate dateStart, LocalDate dateEnd,
            Integer scoreMin, Integer scoreMax, Boolean hasReview) {
        this(type, dateStart, dateEnd, scoreMin, scoreMax, hasReview, null, null, DiaryEntrySort.NEWEST);
    }

    public DiaryViewFilter {
        seriesTmdbId = seriesTmdbId == null ? null : seriesTmdbId.trim();
        sortBy = sortBy == null ? DiaryEntrySort.NEWEST : sortBy;
    }
}
