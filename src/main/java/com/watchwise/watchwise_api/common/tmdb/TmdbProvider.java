package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbProvider(
        @JsonProperty("provider_name") String providerName,
        @JsonProperty("logo_path") String logoPath) {
}
