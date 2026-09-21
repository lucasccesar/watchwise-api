package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbExternalIds(
        @JsonProperty("imdb_id") String imdbId,
        @JsonProperty("facebook_id") String facebookId,
        @JsonProperty("instagram_id") String instagramId,
        @JsonProperty("twitter_id") String twitterId) {
}
