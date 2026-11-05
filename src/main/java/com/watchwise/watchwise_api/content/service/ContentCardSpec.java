package com.watchwise.watchwise_api.content.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ContentCardSpec(
        ContentCoordinate coordinate,
        String title,
        String posterPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        List<String> genres,
        Double tmdbVoteAverage,
        boolean tmdbVoteAverageResolved) {

    public ContentCardSpec(
            ContentCoordinate coordinate,
            String title,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            List<String> genres) {
        this(coordinate, title, posterPath, releaseDate, runtimeMinutes, genres, null, false);
    }

    public ContentCardSpec(
            ContentCoordinate coordinate,
            String title,
            String posterPath,
            LocalDate releaseDate,
            Integer runtimeMinutes) {
        this(coordinate, title, posterPath, releaseDate, runtimeMinutes, null, null, false);
    }

    public ContentCardSpec {
        Objects.requireNonNull(coordinate, "coordinate is required");
        genres = genres == null ? null : List.copyOf(genres);
    }
}
