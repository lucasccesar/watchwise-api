package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbNetwork(
        Integer id,
        String name,
        @JsonProperty("logo_path") String logoPath,
        @JsonProperty("origin_country") String originCountry) {
}
