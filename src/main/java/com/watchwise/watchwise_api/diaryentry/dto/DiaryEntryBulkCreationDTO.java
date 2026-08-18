package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.Map;

public record DiaryEntryBulkCreationDTO(
        @NotNull @Valid ContentRefCreationDTO content,
        LocalDate watchedDate,
        @Min(1) Integer finaleEpisodeNumber,
        @Min(1) Integer finaleSeasonNumber,
        Map<Integer, Integer> seasonFinaleEpisodeNumbers
) {
}
