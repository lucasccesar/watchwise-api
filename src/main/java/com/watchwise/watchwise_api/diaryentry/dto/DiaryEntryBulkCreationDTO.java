package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DiaryEntryBulkCreationDTO(
        @NotNull @Valid ContentRefCreationDTO content,
        LocalDate watchedDate,
        Integer finaleEpisodeNumber,
        Integer finaleSeasonNumber
) {
}
