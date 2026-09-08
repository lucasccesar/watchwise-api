package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMultiSearchResult(
        String id,
        @JsonProperty("media_type") String mediaType,
        String title,
        String name,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("profile_path") String profilePath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("first_air_date") String firstAirDate) {
}
