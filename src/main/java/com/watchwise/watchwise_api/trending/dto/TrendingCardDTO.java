package com.watchwise.watchwise_api.trending.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

import java.util.List;

public record TrendingCardDTO(
        String tmdbId,
        MovieOrSeriesType type,
        String title,
        String posterUrl,
        Integer year,
        List<String> genres,
        Double tmdbVoteAverage,
        Double popularity,
        Integer runtimeMinutes,
        Integer numberOfSeasons,
        ContentCardViewerStateDTO viewerState,
        ContentPreviewStatus previewStatus) {

    public TrendingCardDTO {
        genres = genres == null ? null : List.copyOf(genres);
    }
}
