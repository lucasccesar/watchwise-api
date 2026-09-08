package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

public record SearchContentDTO(
        String tmdbId,
        MovieOrSeriesType type,
        String title,
        String posterUrl,
        Integer year
) {
}
