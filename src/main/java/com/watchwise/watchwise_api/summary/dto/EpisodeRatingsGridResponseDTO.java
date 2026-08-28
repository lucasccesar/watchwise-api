package com.watchwise.watchwise_api.summary.dto;

import java.util.List;

public record EpisodeRatingsGridResponseDTO(
        String seriesTmdbId,
        List<EpisodeScoreDTO> episodes) {
}
