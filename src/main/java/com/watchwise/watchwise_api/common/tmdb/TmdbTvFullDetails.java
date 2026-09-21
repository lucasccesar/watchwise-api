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
        @JsonProperty("alternative_titles") TmdbTvAlternativeTitles alternativeTitles,
        @JsonProperty("number_of_seasons") Integer numberOfSeasons,
        @JsonProperty("number_of_episodes") Integer numberOfEpisodes,
        @JsonProperty("production_companies") List<TmdbProductionCompany> productionCompanies,
        TmdbVideos videos,
        String status,
        @JsonProperty("external_ids") TmdbExternalIds externalIds) {

    public TmdbTvFullDetails(
            String id,
            String name,
            String originalName,
            String overview,
            String posterPath,
            String backdropPath,
            String firstAirDate,
            List<Integer> episodeRunTime,
            List<TmdbGenre> genres,
            List<TmdbProductionCountry> productionCountries,
            List<TmdbCreator> createdBy,
            List<TmdbSeasonSummary> seasons,
            TmdbNextEpisode nextEpisodeToAir,
            TmdbAggregateCredits aggregateCredits,
            TmdbWatchProviders watchProviders,
            TmdbTvAlternativeTitles alternativeTitles,
            Integer numberOfSeasons,
            Integer numberOfEpisodes,
            List<TmdbProductionCompany> productionCompanies,
            TmdbVideos videos,
            String status) {
        this(id, name, originalName, overview, posterPath, backdropPath, firstAirDate, episodeRunTime,
                genres, productionCountries, createdBy, seasons, nextEpisodeToAir, aggregateCredits,
                watchProviders, alternativeTitles, numberOfSeasons, numberOfEpisodes, productionCompanies,
                videos, status, null);
    }
}
