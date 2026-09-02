package com.watchwise.watchwise_api.diaryentry.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.constraints.Size;

public record DiaryEntryBulkCreationDTO(
        @NotNull @Valid ContentRefCreationDTO content,
        LocalDate watchedDate,
        @Min(1) @Max(100) Integer finaleEpisodeNumber,
        @Min(1) @Max(100) Integer finaleSeasonNumber,
        Map<@Min(1) @Max(100) Integer, @Min(1) @Max(100) Integer> seasonFinaleEpisodeNumbers,
        @Size(max = 20) List<UUID> watchedWith,
        Map<@Min(1) @Max(100) Integer, @Positive @Max(1200) Integer> episodeRuntimeMinutes
) {
    public DiaryEntryBulkCreationDTO(ContentRefCreationDTO content, LocalDate watchedDate, Integer finaleEpisodeNumber,
            Integer finaleSeasonNumber, Map<Integer, Integer> seasonFinaleEpisodeNumbers) {
        this(content, watchedDate, finaleEpisodeNumber, finaleSeasonNumber, seasonFinaleEpisodeNumbers, null, null);
    }

    public DiaryEntryBulkCreationDTO(ContentRefCreationDTO content, LocalDate watchedDate, Integer finaleEpisodeNumber,
            Integer finaleSeasonNumber, Map<Integer, Integer> seasonFinaleEpisodeNumbers, List<UUID> watchedWith) {
        this(content, watchedDate, finaleEpisodeNumber, finaleSeasonNumber, seasonFinaleEpisodeNumbers, watchedWith, null);
    }
}
