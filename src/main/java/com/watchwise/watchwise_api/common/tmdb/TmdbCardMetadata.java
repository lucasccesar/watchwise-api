package com.watchwise.watchwise_api.common.tmdb;

public record TmdbCardMetadata(
        String title,
        String posterPath,
        String releaseDate,
        Integer runtimeMinutes) {
}
