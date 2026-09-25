package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface SeriesProgressReader {

    Map<String, SeriesInProgressResponseDTO> readForSeriesIds(
            UUID viewerId, Collection<String> seriesTmdbIds);
}
