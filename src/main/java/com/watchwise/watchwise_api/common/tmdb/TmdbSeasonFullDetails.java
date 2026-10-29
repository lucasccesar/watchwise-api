package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbSeasonFullDetails(
        Integer id,
        String name,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("air_date") String airDate,
        @JsonProperty("season_number") Integer seasonNumber,
        List<TmdbEpisodeSummary> episodes,
        @JsonProperty("aggregate_credits") TmdbAggregateCredits aggregateCredits,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
        @JsonProperty("vote_average") Double voteAverage) {

    public TmdbSeasonFullDetails(
            Integer id,
            String name,
            String overview,
            String posterPath,
            String airDate,
            Integer seasonNumber,
            List<TmdbEpisodeSummary> episodes,
            TmdbAggregateCredits aggregateCredits,
            TmdbWatchProviders watchProviders) {
        this(id, name, overview, posterPath, airDate, seasonNumber, episodes, aggregateCredits,
                watchProviders, null);
    }
}
