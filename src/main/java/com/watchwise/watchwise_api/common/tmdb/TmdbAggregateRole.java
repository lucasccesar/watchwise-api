package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbAggregateRole(
        String character,
        @com.fasterxml.jackson.annotation.JsonProperty("episode_count") Integer episodeCount,
        @com.fasterxml.jackson.annotation.JsonProperty("credit_id") String creditId) {

    public TmdbAggregateRole(String character) {
        this(character, null, null);
    }

    public TmdbAggregateRole(String character, Integer episodeCount) {
        this(character, episodeCount, null);
    }
}
