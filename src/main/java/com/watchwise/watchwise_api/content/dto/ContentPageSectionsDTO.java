package com.watchwise.watchwise_api.content.dto;

import java.util.List;

public record ContentPageSectionsDTO(
        List<ContentChildCardDTO> seasons,
        List<ContentChildCardDTO> episodes,
        List<ContentChildCardDTO> recentEpisodes) {

    public ContentPageSectionsDTO {
        seasons = seasons == null ? List.of() : List.copyOf(seasons);
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
        recentEpisodes = recentEpisodes == null ? List.of() : List.copyOf(recentEpisodes);
    }
}
