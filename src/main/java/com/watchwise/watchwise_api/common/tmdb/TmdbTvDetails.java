package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvDetails(
        String id,
        String status,
        @JsonProperty("next_episode_to_air") TmdbNextEpisode nextEpisodeToAir) {
}
