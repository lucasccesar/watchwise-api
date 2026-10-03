package com.watchwise.watchwise_api.watchlist.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record WatchlistPageResponseDTO(
        List<WatchlistEntryResponseDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long upcomingCount
) {
    public static WatchlistPageResponseDTO of(Page<WatchlistEntryResponseDTO> page, long upcomingCount) {
        return new WatchlistPageResponseDTO(
                page.getContent(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                upcomingCount
        );
    }
}
