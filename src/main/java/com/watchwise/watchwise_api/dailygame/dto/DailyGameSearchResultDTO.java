package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;

import java.time.LocalDate;

public record DailyGameSearchResultDTO(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String imageUrl,
        LocalDate date) {
}
