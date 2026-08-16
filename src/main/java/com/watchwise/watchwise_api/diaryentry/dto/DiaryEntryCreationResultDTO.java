package com.watchwise.watchwise_api.diaryentry.dto;

public record DiaryEntryCreationResultDTO(
        DiaryEntryResponseDTO entry,
        DiaryEntryResponseDTO completedSeason,
        DiaryEntryResponseDTO completedSeries
) {
}
