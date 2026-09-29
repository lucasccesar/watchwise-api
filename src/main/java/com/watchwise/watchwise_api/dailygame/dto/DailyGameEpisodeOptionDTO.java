package com.watchwise.watchwise_api.dailygame.dto;

import java.time.LocalDate;

public record DailyGameEpisodeOptionDTO(
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber,
        String name,
        LocalDate airDate) {
}
