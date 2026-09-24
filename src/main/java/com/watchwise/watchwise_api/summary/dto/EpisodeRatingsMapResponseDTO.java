package com.watchwise.watchwise_api.summary.dto;

import java.util.List;

public record EpisodeRatingsMapResponseDTO(
        List<EpisodeRatingsMapItemDTO> series) {

    public EpisodeRatingsMapResponseDTO {
        series = List.copyOf(series);
    }
}
