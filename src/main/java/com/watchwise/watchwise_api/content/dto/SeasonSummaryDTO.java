package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record SeasonSummaryDTO(
        Integer seasonNumber,
        String name,
        String posterPath,
        LocalDate airDate,
        Integer episodeCount,
        Integer airedEpisodeCount,
        Double tmdbVoteAverage) {

    public SeasonSummaryDTO(
            Integer seasonNumber,
            String name,
            String posterPath,
            LocalDate airDate,
            Integer episodeCount,
            Integer airedEpisodeCount) {
        this(seasonNumber, name, posterPath, airDate, episodeCount, airedEpisodeCount, null);
    }
}
