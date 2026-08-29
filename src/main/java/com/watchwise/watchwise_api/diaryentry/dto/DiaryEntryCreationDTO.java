package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DiaryEntryCreationDTO(
        @NotNull @Valid ContentRefCreationDTO content,
        String comment,
        @Min(1) @Max(10) Integer score,
        LocalDate watchedDate,
        Boolean isRewatch,
        Boolean watchedInTheater,
        @Size(max = 2048) @URL String customPosterUrl,
        @Size(max = 20) List<UUID> watchedWith
) {
    public DiaryEntryCreationDTO(ContentRefCreationDTO content, String comment, Integer score, LocalDate watchedDate,
            Boolean isRewatch, Boolean watchedInTheater, String customPosterUrl) {
        this(content, comment, score, watchedDate, isRewatch, watchedInTheater, customPosterUrl, null);
    }
}
