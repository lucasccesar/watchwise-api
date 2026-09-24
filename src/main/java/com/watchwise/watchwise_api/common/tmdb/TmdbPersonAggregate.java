package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonAggregate(
        String id,
        String name,
        String biography,
        String birthday,
        String deathday,
        @JsonProperty("place_of_birth") String placeOfBirth,
        String gender,
        @JsonProperty("profile_path") String profilePath,
        @JsonProperty("known_for_department") String knownForDepartment,
        @JsonProperty("also_known_as") List<String> alsoKnownAs,
        @JsonProperty("combined_credits") TmdbPersonAggregateCredits combinedCredits) {
}
