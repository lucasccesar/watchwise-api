package com.watchwise.watchwise_api.dailygame.dto;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;

import java.util.List;

public record DailyGameCandidateViewDTO(
        DailyGameTargetKind targetKind, String tmdbId, String personTmdbId,
        String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
        String title, String imageUrl, Integer year, List<String> genres,
        Integer numberOfSeasons) {}
