package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;

public record DiaryViewFilter(
        ContentType type,
        LocalDate dateStart,
        LocalDate dateEnd,
        Integer scoreMin,
        Integer scoreMax,
        Boolean hasReview) {
}
