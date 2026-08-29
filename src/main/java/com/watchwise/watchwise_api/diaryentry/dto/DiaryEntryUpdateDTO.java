package com.watchwise.watchwise_api.diaryentry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DiaryEntryUpdateDTO(
        String comment,
        @Min(1) @Max(10) Integer score,
        LocalDate watchedDate,
        Boolean watchedInTheater,
        @Size(max = 2048) @URL String customPosterUrl,
        @Size(max = 20) List<UUID> watchedWith
) {
    public DiaryEntryUpdateDTO(String comment, Integer score, LocalDate watchedDate, Boolean watchedInTheater,
            String customPosterUrl) {
        this(comment, score, watchedDate, watchedInTheater, customPosterUrl, null);
    }
}
