package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTrendingMovieResult(
        String id,
        String title,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("genre_ids") List<Integer> genreIds,
        @JsonProperty("vote_average") Double voteAverage,
        Double popularity,
        Integer runtime) {

    public TmdbTrendingMovieResult(
            String id,
            String title,
            String posterPath,
            String releaseDate,
            List<Integer> genreIds,
            Double voteAverage,
            Double popularity) {
        this(id, title, posterPath, releaseDate, genreIds, voteAverage, popularity, null);
    }

    public TmdbTrendingMovieResult(
            String id, String title, String posterPath, String releaseDate) {
        this(id, title, posterPath, releaseDate, null, null, null, null);
    }
}
