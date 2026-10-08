package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTrendingTvResult(
        String id,
        String name,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("first_air_date") String firstAirDate,
        @JsonProperty("genre_ids") List<Integer> genreIds,
        @JsonProperty("vote_average") Double voteAverage,
        Double popularity,
        @JsonProperty("number_of_seasons") Integer numberOfSeasons) {

    public TmdbTrendingTvResult(
            String id,
            String name,
            String posterPath,
            String firstAirDate,
            List<Integer> genreIds,
            Double voteAverage,
            Double popularity) {
        this(id, name, posterPath, firstAirDate, genreIds, voteAverage, popularity, null);
    }

    public TmdbTrendingTvResult(
            String id, String name, String posterPath, String firstAirDate) {
        this(id, name, posterPath, firstAirDate, null, null, null, null);
    }
}
