package com.watchwise.watchwise_api.content.service;

import java.time.LocalDate;

public record ContentScheduleEpisode(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        LocalDate releaseDate,
        String title,
        String stillPath) {

    public ContentScheduleEpisode(
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            LocalDate releaseDate) {
        this(seriesTmdbId, seasonNumber, episodeNumber, releaseDate, null, null);
    }
}
