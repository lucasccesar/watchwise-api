package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;

import java.util.List;

public interface TmdbSeriesHierarchyService {
    List<ContentSeasonSelectionDTO> listSeasons(String seriesTmdbId, boolean includeSpecials, String language);

    List<ContentEpisodeSelectionDTO> listEpisodes(
            String seriesTmdbId, int seasonNumber, boolean includeUnreleased, String language);
}
