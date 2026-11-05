package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record EpisodeSummaryDTO(
        Integer seasonNumber,
        Integer episodeNumber,
        String name,
        LocalDate airDate,
        Integer runtime,
        String stillPath,
        Double tmdbVoteAverage) {

    public EpisodeSummaryDTO(
            Integer seasonNumber,
            Integer episodeNumber,
            String name,
            LocalDate airDate,
            Integer runtime,
            String stillPath) {
        this(seasonNumber, episodeNumber, name, airDate, runtime, stillPath, null);
    }
}
