package com.watchwise.watchwise_api.diaryentry.dto;

import java.util.List;

public record SeriesInProgressPageResponseDTO(
        List<SeriesInProgressResponseDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        SeriesInProgressAggregateDTO aggregate) {

    public SeriesInProgressPageResponseDTO {
        content = List.copyOf(content);
    }
}
