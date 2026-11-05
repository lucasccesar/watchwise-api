package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record EpisodeSummaryDTO(
        Integer seasonNumber,
        Integer episodeNumber,
        String name,
        String overview,
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
            String stillPath,
            Double tmdbVoteAverage) {
        this(seasonNumber, episodeNumber, name, null, airDate, runtime, stillPath, tmdbVoteAverage);
    }

    public EpisodeSummaryDTO(
            Integer seasonNumber,
            Integer episodeNumber,
            String name,
            LocalDate airDate,
            Integer runtime,
            String stillPath) {
        this(seasonNumber, episodeNumber, name, null, airDate, runtime, stillPath, null);
    }
}
