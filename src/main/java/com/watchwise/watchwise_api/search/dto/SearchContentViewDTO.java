package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

import java.util.List;

public record SearchContentViewDTO(
        String tmdbId,
        MovieOrSeriesType type,
        String title,
        String posterUrl,
        Integer releaseYear,
        List<String> genres,
        Integer runtimeMinutes,
        Integer numberOfSeasons,
        Double voteAverage,
        ContentCardViewerStateDTO viewerState,
        ContentPreviewStatus previewStatus) {
}
