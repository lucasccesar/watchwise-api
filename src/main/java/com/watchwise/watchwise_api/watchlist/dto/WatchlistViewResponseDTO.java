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
        String backdropPath,
        List<String> availableGenres) {

    public WatchlistViewResponseDTO {
        content = content == null ? List.of() : List.copyOf(content);
        availableGenres = availableGenres == null ? List.of() : List.copyOf(availableGenres);
    }

    public WatchlistViewResponseDTO(List<WatchlistCardDTO> content, int page, int size, long totalElements,
            int totalPages, boolean hasNext, WatchlistAggregateDTO aggregate, String backdropPath) {
        this(content, page, size, totalElements, totalPages, hasNext, aggregate, backdropPath, List.of());
    }

    public WatchlistViewResponseDTO(List<WatchlistCardDTO> content, int page, int size, long totalElements,
            int totalPages, boolean hasNext, WatchlistAggregateDTO aggregate) {
        this(content, page, size, totalElements, totalPages, hasNext, aggregate, null, List.of());
    }
}
