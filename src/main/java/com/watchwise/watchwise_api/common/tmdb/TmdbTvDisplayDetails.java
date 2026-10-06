package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvDisplayDetails(
        String id,
        String name,
        @JsonProperty("first_air_date") String firstAirDate,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        List<TmdbSeasonSummary> seasons) {
    public TmdbTvDisplayDetails(String id, String name, String firstAirDate, String posterPath, String backdropPath) {
        this(id, name, firstAirDate, posterPath, backdropPath, List.of());
    }
}
