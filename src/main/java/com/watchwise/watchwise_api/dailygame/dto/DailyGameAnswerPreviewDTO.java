package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;

import java.time.LocalDate;
import java.util.List;

public record DailyGameAnswerPreviewDTO(
        DailyGameTargetKind targetKind, String tmdbId, String personTmdbId,
        String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
        String title, DailyGameMediaRefDTO image, Integer year, List<String> genres,
        String director, List<String> creators, List<String> knownForTitles,
        String seriesName, DailyGameMediaRefDTO seriesPoster, Integer seriesYear,
        LocalDate airDate) {}
