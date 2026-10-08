package com.watchwise.watchwise_api.common.tmdb;

import java.util.List;

public record TmdbCardMetadata(
        String title,
        String posterPath,
        String releaseDate,
        Integer runtimeMinutes,
        List<String> genres,
        Integer numberOfSeasons) {

    public TmdbCardMetadata(
            String title, String posterPath, String releaseDate, Integer runtimeMinutes) {
        this(title, posterPath, releaseDate, runtimeMinutes, null, null);
    }

    public TmdbCardMetadata {
        genres = genres == null ? null : List.copyOf(genres);
    }
}
