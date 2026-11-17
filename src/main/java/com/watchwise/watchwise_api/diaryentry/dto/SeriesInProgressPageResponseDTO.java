package com.watchwise.watchwise_api.diaryentry.dto;

import java.util.List;

public record SeriesInProgressPageResponseDTO(
        List<SeriesInProgressResponseDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        SeriesInProgressAggregateDTO aggregate,
        String backdropPath) {

    public SeriesInProgressPageResponseDTO {
        content = List.copyOf(content);
    }

    public SeriesInProgressPageResponseDTO(
            List<SeriesInProgressResponseDTO> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext,
            SeriesInProgressAggregateDTO aggregate) {
        this(content, page, size, totalElements, totalPages, hasNext, aggregate, null);
    }
}
