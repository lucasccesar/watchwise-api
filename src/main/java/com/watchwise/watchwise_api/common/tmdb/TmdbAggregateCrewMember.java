package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbAggregateCrewMember(
        Integer id,
        String name,
        @JsonProperty("profile_path") String profilePath,
        List<TmdbAggregateCrewJob> jobs) {
}
