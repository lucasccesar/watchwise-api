package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;

public record DiaryEntryCreationDTO(
        @NotNull @Valid ContentRefCreationDTO content,
        String comment,
        @Min(1) @Max(10) Integer score,
        LocalDate watchedDate,
        Boolean isRewatch,
        Boolean watchedInTheater,
        @Size(max = 2048) @URL String customPosterUrl
) {
}