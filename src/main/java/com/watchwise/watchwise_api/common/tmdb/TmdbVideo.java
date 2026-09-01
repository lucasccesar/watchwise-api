package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbVideo(
        String key,
        String name,
        String site,
        String type,
        Boolean official,
        @JsonProperty("iso_639_1") String isoCode639_1,
        @JsonProperty("published_at") String publishedAt) {
}
