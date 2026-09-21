package com.watchwise.watchwise_api.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContentDetailsDTO(
        UUID contentId,
        ContentType type,
        String title,
        String overview,
        String posterPath,
        String backdropPath,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        Integer totalRuntimeMinutes,
        Integer numberOfSeasons,
        Integer numberOfEpisodes,
        List<String> genres,
        List<String> countries,
        List<CastMemberDTO> cast,
        List<CastMemberDTO> guestStars,
        List<CreatorDTO> creators,
        List<WatchProviderDTO> watchProviders,
        List<SeasonSummaryDTO> seasons,
        List<EpisodeSummaryDTO> episodes,
        List<EpisodeSummaryDTO> recentEpisodes,
        Long budget,
        Long revenue,
        List<ProductionCompanyDTO> productionCompanies,
        List<CrewMemberDTO> crew,
        List<VideoDTO> videos,
        @JsonProperty("imdb_id") String imdbId,
        @JsonProperty("facebook_id") String facebookId,
        @JsonProperty("instagram_id") String instagramId,
        @JsonProperty("twitter_id") String twitterId) {

    public ContentDetailsDTO(
            UUID contentId,
            ContentType type,
            String title,
            String overview,
            String posterPath,
            String backdropPath,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            Integer totalRuntimeMinutes,
            Integer numberOfSeasons,
            Integer numberOfEpisodes,
            List<String> genres,
            List<String> countries,
            List<CastMemberDTO> cast,
            List<CastMemberDTO> guestStars,
            List<CreatorDTO> creators,
            List<WatchProviderDTO> watchProviders,
            List<SeasonSummaryDTO> seasons,
            List<EpisodeSummaryDTO> episodes,
            List<EpisodeSummaryDTO> recentEpisodes,
            Long budget,
            Long revenue,
            List<ProductionCompanyDTO> productionCompanies,
            List<CrewMemberDTO> crew,
            List<VideoDTO> videos) {
        this(contentId, type, title, overview, posterPath, backdropPath, releaseDate, runtimeMinutes,
                totalRuntimeMinutes, numberOfSeasons, numberOfEpisodes, genres, countries, cast, guestStars,
                creators, watchProviders, seasons, episodes, recentEpisodes, budget, revenue,
                productionCompanies, crew, videos, null, null, null, null);
    }
}
