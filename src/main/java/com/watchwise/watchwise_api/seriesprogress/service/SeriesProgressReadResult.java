package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SeriesProgressReadResult(
        Map<String, SeriesInProgressResponseDTO> progressBySeriesId,
        String backdropPath) {

    public SeriesProgressReadResult {
        progressBySeriesId = Collections.unmodifiableMap(new LinkedHashMap<>(progressBySeriesId));
    }

    public static SeriesProgressReadResult empty() {
        return new SeriesProgressReadResult(Map.of(), null);
    }
}
