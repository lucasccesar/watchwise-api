package com.watchwise.watchwise_api.content.service;

import java.time.LocalDate;
import java.time.LocalTime;

public record ContentScheduleEpisode(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        LocalDate releaseDate,
        LocalTime releaseTime,
        String title,
        String stillPath) {

    public ContentScheduleEpisode(
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            LocalDate releaseDate) {
        this(seriesTmdbId, seasonNumber, episodeNumber, releaseDate, null, null, null);
    }

    public ContentScheduleEpisode(
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            LocalDate releaseDate,
            String title,
            String stillPath) {
        this(seriesTmdbId, seasonNumber, episodeNumber, releaseDate, null, title, stillPath);
    }
}
