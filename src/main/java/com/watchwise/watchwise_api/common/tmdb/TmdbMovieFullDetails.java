package com.watchwise.watchwise_api.common.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovieFullDetails(
        String id,
        String title,
        @JsonProperty("original_title") String originalTitle,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("backdrop_path") String backdropPath,
        @JsonProperty("release_date") String releaseDate,
        Integer runtime,
        List<TmdbGenre> genres,
        @JsonProperty("production_countries") List<TmdbProductionCountry> productionCountries,
        TmdbCredits credits,
        @JsonProperty("watch/providers") TmdbWatchProviders watchProviders,
        @JsonProperty("alternative_titles") TmdbMovieAlternativeTitles alternativeTitles,
        Long budget,
        Long revenue,
        @JsonProperty("production_companies") List<TmdbProductionCompany> productionCompanies,
        TmdbVideos videos,
        String status,
        @JsonProperty("external_ids") TmdbExternalIds externalIds) {

    public TmdbMovieFullDetails(
            String id,
            String title,
            String originalTitle,
            String overview,
            String posterPath,
            String backdropPath,
            String releaseDate,
            Integer runtime,
            List<TmdbGenre> genres,
            List<TmdbProductionCountry> productionCountries,
            TmdbCredits credits,
            TmdbWatchProviders watchProviders,
            TmdbMovieAlternativeTitles alternativeTitles,
            Long budget,
            Long revenue,
            List<TmdbProductionCompany> productionCompanies,
            TmdbVideos videos) {
        this(id, title, originalTitle, overview, posterPath, backdropPath, releaseDate, runtime,
                genres, productionCountries, credits, watchProviders, alternativeTitles, budget,
                revenue, productionCompanies, videos, null, null);
    }
}
