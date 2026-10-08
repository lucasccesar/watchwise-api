package com.watchwise.watchwise_api.watchlist.dto;

import java.util.List;

public record WatchlistViewResponseDTO(
        List<WatchlistCardDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        WatchlistAggregateDTO aggregate) {

    public WatchlistViewResponseDTO {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
