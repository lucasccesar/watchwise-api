package com.watchwise.watchwise_api.seriesprogress.dto;

import java.time.LocalDate;

public record ProgressEpisodeDTO(
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        LocalDate releaseDate,
        Integer runtimeMinutes,
        String stillPath,
        Boolean availableToWatch) {
}
