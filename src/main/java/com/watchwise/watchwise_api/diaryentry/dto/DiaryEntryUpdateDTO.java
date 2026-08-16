package com.watchwise.watchwise_api.diaryentry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;

public record DiaryEntryUpdateDTO(
        String comment,
        @Min(1) @Max(10) Integer score,
        LocalDate watchedDate,
        Boolean watchedInTheater,
        @Size(max = 2048) @URL String customPosterUrl
) {
}