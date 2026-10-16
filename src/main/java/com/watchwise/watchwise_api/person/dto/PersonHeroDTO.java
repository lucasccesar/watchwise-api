package com.watchwise.watchwise_api.person.dto;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

public record PersonHeroDTO(
        MovieOrSeriesType type,
        String tmdbId,
        String title,
        Integer releaseYear,
        String backdropUrl) {
}
