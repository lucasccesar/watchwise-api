package com.watchwise.watchwise_api.summary.dto;

import java.util.List;

public record EpisodeRatingsMapResponseDTO(
        List<EpisodeRatingsMapItemDTO> series,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long totalWatchedEpisodeCount,
        Integer totalEpisodeCount) {

    public EpisodeRatingsMapResponseDTO {
        series = List.copyOf(series);
    }

    public EpisodeRatingsMapResponseDTO(List<EpisodeRatingsMapItemDTO> series) {
        this(series, 1, series.size(), series.size(), series.isEmpty() ? 0 : 1, false,
                series.stream()
                        .map(EpisodeRatingsMapItemDTO::watchedEpisodeCount)
                        .filter(count -> count != null)
                        .mapToLong(Long::longValue)
                        .sum(),
                null);
    }
}
