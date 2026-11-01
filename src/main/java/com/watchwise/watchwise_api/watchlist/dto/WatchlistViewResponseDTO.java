package com.watchwise.watchwise_api.watchlist.dto;

import java.util.List;

public record WatchlistViewResponseDTO(
        List<WatchlistCardDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        WatchlistAggregateDTO aggregate,
        String backdropPath) {

    public WatchlistViewResponseDTO {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public WatchlistViewResponseDTO(List<WatchlistCardDTO> content, int page, int size, long totalElements,
            int totalPages, boolean hasNext, WatchlistAggregateDTO aggregate) {
        this(content, page, size, totalElements, totalPages, hasNext, aggregate, null);
    }
}
