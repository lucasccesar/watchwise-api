package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonDetails(
        String id,
        String name,
        @JsonProperty("profile_path") String profilePath,
        String birthday) {

    public TmdbPersonDetails(String id) {
        this(id, null, null, null);
    }
}
