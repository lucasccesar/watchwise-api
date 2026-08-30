package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbSeasonSummary(
        @JsonProperty("season_number") Integer seasonNumber,
        String name,
        String overview,
        @JsonProperty("air_date") String airDate,
        @JsonProperty("episode_count") Integer episodeCount,
        @JsonProperty("poster_path") String posterPath) {
}
