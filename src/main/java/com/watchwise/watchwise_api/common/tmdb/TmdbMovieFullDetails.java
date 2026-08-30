package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieFullDetails(
        String id,
        String title,
        @JsonProperty("original_title") String originalTitle,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("release_date") String releaseDate,
        Integer runtime,
        List<TmdbGenre> genres,
        @JsonProperty("production_countries") List<TmdbProductionCountry> productionCountries,
        TmdbCredits credits,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
        @JsonProperty("alternative_titles") TmdbMovieAlternativeTitles alternativeTitles) {
}
