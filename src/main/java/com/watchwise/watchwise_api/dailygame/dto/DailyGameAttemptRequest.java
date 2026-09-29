package com.watchwise.watchwise_api.dailygame.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record DailyGameAttemptRequest(
        @Pattern(regexp = "[1-9]\\d*", message = "must be a positive numeric identifier")
        String tmdbId,
        @Pattern(regexp = "[1-9]\\d*", message = "must be a positive numeric identifier")
        String personTmdbId,
        @Pattern(regexp = "[1-9]\\d*", message = "must be a positive numeric identifier")
        String seriesTmdbId,
        @Min(value = 1, message = "must be greater than zero")
        Integer seasonNumber,
        @Min(value = 1, message = "must be greater than zero")
        Integer episodeNumber,
        Boolean shareOnCompletion) {

    public DailyGameAttemptRequest(
            String tmdbId,
            String personTmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber) {
        this(tmdbId, personTmdbId, seriesTmdbId, seasonNumber, episodeNumber, null);
    }
}
