package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;

public record DailyGameAnswerDTO(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String imageUrl,
        String seriesName,
        String seriesPosterUrl,
        Integer seriesYear) {

    public DailyGameAnswerDTO(
            DailyGameTargetKind targetKind,
            String tmdbId,
            String personTmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            String title,
            String imageUrl) {
        this(targetKind, tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber, title, imageUrl,
                null, null, null);
    }
}
