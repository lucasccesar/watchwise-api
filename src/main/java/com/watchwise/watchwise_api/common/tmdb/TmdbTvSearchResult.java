package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvSearchResult(
        String id,
        String name,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("first_air_date") String firstAirDate) {
}
