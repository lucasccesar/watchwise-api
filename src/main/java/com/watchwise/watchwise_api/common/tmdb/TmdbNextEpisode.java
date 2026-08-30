package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbNextEpisode(
        @JsonProperty("air_date") String airDate,
        @JsonProperty("season_number") Integer seasonNumber,
        @JsonProperty("episode_number") Integer episodeNumber) {
}
