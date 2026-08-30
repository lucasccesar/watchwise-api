package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTvFullDetails(
        String id,
        String name,
        @JsonProperty("original_name") String originalName,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("first_air_date") String firstAirDate,
        @JsonProperty("episode_run_time") List<Integer> episodeRunTime,
        List<TmdbGenre> genres,
        @JsonProperty("production_countries") List<TmdbProductionCountry> productionCountries,
        @JsonProperty("created_by") List<TmdbCreator> createdBy,
        List<TmdbSeasonSummary> seasons,
        @JsonProperty("next_episode_to_air") TmdbNextEpisode nextEpisodeToAir,
        @JsonProperty("aggregate_credits") TmdbAggregateCredits aggregateCredits,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
        @JsonProperty("alternative_titles") TmdbTvAlternativeTitles alternativeTitles) {
}
