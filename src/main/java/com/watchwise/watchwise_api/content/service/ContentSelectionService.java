package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;

import java.util.List;
import java.util.UUID;

public interface ContentSelectionService {
    List<ContentSeasonSelectionDTO> listSeasons(UUID viewerId, String seriesTmdbId, boolean includeSpecials);

    List<ContentEpisodeSelectionDTO> listEpisodes(
            UUID viewerId, String seriesTmdbId, int seasonNumber, boolean includeUnreleased);
}
