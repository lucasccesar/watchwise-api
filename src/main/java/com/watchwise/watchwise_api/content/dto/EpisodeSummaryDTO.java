package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record EpisodeSummaryDTO(
        Integer episodeNumber,
        String name,
        LocalDate airDate,
        Integer runtime,
        String stillPath) {
}
