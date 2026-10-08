package com.watchwise.watchwise_api.trending.dto;

import java.util.List;

public record TrendingResponseDTO(
        List<TrendingCardDTO> movies,
        List<TrendingCardDTO> series,
        SectionPage moviesPage,
        SectionPage seriesPage) {

    public TrendingResponseDTO(List<TrendingCardDTO> movies, List<TrendingCardDTO> series) {
        this(movies, series, null, null);
    }

    public TrendingResponseDTO {
        movies = movies == null ? List.of() : List.copyOf(movies);
        series = series == null ? List.of() : List.copyOf(series);
    }

    public record SectionPage(
            int page,
            int size,
            int totalPages,
            long totalResults,
            boolean hasNext) {
    }
}
