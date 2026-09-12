package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbRegionReleaseDates(
        @JsonProperty("iso_3166_1") String isoCode,
        @JsonProperty("release_dates") List<TmdbMovieReleaseDate> releaseDates) {
}
