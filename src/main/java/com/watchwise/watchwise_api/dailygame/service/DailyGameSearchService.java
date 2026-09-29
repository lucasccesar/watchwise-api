package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.dailygame.dto.DailyGameEpisodeOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSeasonOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface DailyGameSearchService {

    Page<DailyGameSearchResultDTO> search(
            UUID userId, DailyGameType type, String query, Integer page, Integer size);

    Page<DailyGameSearchResultDTO> searchEpisodeSeries(
            UUID userId, String query, Integer page, Integer size);

    List<DailyGameSeasonOptionDTO> listEpisodeSeasons(UUID userId, String seriesTmdbId);

    List<DailyGameEpisodeOptionDTO> listEpisodeEpisodes(
            UUID userId, String seriesTmdbId, Integer seasonNumber);
}
