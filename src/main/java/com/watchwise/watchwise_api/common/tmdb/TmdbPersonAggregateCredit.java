package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbPersonAggregateCredit(
        String id,
        @JsonProperty("media_type") String mediaType,
        String title,
        String name,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("first_air_date") String firstAirDate,
        String character,
        String job,
        @JsonProperty("genre_ids") List<Integer> genreIds,
        @JsonProperty("episode_count") Integer episodeCount,
        List<TmdbAggregateRole> roles,
        @JsonProperty("backdrop_path") String backdropPath,
        Double popularity) {

    public TmdbPersonAggregateCredit(
            String id,
            String mediaType,
            String title,
            String name,
            String posterPath,
            String releaseDate,
            String firstAirDate,
            String character,
            String job) {
        this(id, mediaType, title, name, posterPath, releaseDate, firstAirDate, character, job,
                null, null, null, null, null);
    }

    public TmdbPersonAggregateCredit(String id, String mediaType, String title, String name, String posterPath,
                                     String releaseDate, String firstAirDate, String character, String job,
                                     List<Integer> genreIds, Integer episodeCount, List<TmdbAggregateRole> roles) {
        this(id, mediaType, title, name, posterPath, releaseDate, firstAirDate, character, job,
                genreIds, episodeCount, roles, null, null);
    }

}
