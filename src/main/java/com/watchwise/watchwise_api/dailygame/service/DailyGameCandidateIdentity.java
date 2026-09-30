package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;

import java.time.LocalDate;

public record DailyGameCandidateIdentity(
        DailyGameTargetKind targetKind,
        String tmdbId,
        String personTmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String imageUrl,
        LocalDate date) {

    public DailyGameCandidateIdentity(
            DailyGameTargetKind targetKind,
            String tmdbId,
            String personTmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber) {
        this(targetKind, tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber,
                null, null, null);
    }

    public String answerKey() {
        return switch (targetKind) {
            case MOVIE -> "MOVIE:" + tmdbId;
            case SERIES -> "SERIES:" + tmdbId;
            case PERSON -> "PERSON:" + personTmdbId;
            case EPISODE -> "EPISODE:" + seriesTmdbId + ":" + seasonNumber + ":" + episodeNumber;
        };
    }
}
