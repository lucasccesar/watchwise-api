package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieReleaseDate(
        String certification,
        @JsonProperty("iso_639_1") String isoLanguage,
        @JsonProperty("release_date") String releaseDate,
        String note,
        Integer type) {
}
