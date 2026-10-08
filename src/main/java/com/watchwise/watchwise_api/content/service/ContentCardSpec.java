package com.watchwise.watchwise_api.content.service;

import java.time.LocalDate;
import java.util.Objects;

public record ContentCardSpec(
        ContentCoordinate coordinate,
        String title,
        String posterPath,
        LocalDate releaseDate,
        Integer runtimeMinutes) {

    public ContentCardSpec {
        Objects.requireNonNull(coordinate, "coordinate is required");
    }
}
