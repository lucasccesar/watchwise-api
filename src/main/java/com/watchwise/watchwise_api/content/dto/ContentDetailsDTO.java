package com.watchwise.watchwise_api.content.dto;

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
        List<EpisodeSummaryDTO> recentEpisodes) {
}
