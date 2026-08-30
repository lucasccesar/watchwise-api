package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbSeasonFullDetails(
        Integer id,
        String name,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("air_date") String airDate,
        @JsonProperty("season_number") Integer seasonNumber,
        List<TmdbEpisodeSummary> episodes,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders) {
}
