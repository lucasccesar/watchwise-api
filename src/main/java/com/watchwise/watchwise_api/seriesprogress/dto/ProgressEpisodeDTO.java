package com.watchwise.watchwise_api.seriesprogress.dto;

import java.time.LocalDate;

public record ProgressEpisodeDTO(
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        String stillPath,
        Double tmdbVoteAverage,
        Boolean availableToWatch) {

    public ProgressEpisodeDTO(
            Integer seasonNumber,
            Integer episodeNumber,
            String title,
            LocalDate releaseDate,
            Integer runtimeMinutes,
            String stillPath,
            Boolean availableToWatch) {
        this(seasonNumber, episodeNumber, title, releaseDate, runtimeMinutes, stillPath, null, availableToWatch);
    }
}
