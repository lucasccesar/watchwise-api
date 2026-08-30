package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbEpisodeFullDetails(
        Integer id,
        String name,
        String overview,
        @JsonProperty("air_date") String airDate,
        @JsonProperty("episode_number") Integer episodeNumber,
        @JsonProperty("season_number") Integer seasonNumber,
        Integer runtime,
        @JsonProperty("still_path") String stillPath,
        @JsonProperty("guest_stars") List<TmdbGuestStar> guestStars) {
}
