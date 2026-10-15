package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

public record DailyGameSearchResultDTO(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String imageUrl,
        LocalDate date,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> genres,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer numberOfSeasons) {

    public DailyGameSearchResultDTO(
            DailyGameTargetKind targetKind, String tmdbId, String personTmdbId, String seriesTmdbId,
            Integer seasonNumber, Integer episodeNumber, String title, String imageUrl, LocalDate date) {
        this(targetKind, tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber,
                title, imageUrl, date, null, null);
    }

    public DailyGameSearchResultDTO {
        genres = genres == null ? null : List.copyOf(genres);
    }
}
