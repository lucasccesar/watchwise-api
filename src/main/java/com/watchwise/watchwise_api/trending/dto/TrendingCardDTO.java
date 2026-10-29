package com.watchwise.watchwise_api.trending.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

import java.time.LocalDate;
import java.util.List;

public record TrendingCardDTO(
        String tmdbId,
        MovieOrSeriesType type,
        String title,
        String posterUrl,
        String backdropPath,
        Integer year,
        LocalDate releaseDate,
        List<String> genres,
        Double tmdbVoteAverage,
        Double communityAverageScore,
        Double popularity,
        Integer runtimeMinutes,
        Integer numberOfSeasons,
        ContentCardViewerStateDTO viewerState,
        ContentPreviewStatus previewStatus) {

    public TrendingCardDTO {
        genres = genres == null ? null : List.copyOf(genres);
    }

    public TrendingCardDTO(
            String tmdbId,
            MovieOrSeriesType type,
            String title,
            String posterUrl,
            String backdropPath,
            Integer year,
            List<String> genres,
            Double tmdbVoteAverage,
            Double communityAverageScore,
            Double popularity,
            Integer runtimeMinutes,
            Integer numberOfSeasons,
            ContentCardViewerStateDTO viewerState,
            ContentPreviewStatus previewStatus) {
        this(tmdbId, type, title, posterUrl, backdropPath, year, null, genres, tmdbVoteAverage,
                communityAverageScore, popularity, runtimeMinutes, numberOfSeasons, viewerState, previewStatus);
    }

    public TrendingCardDTO(
            String tmdbId,
            MovieOrSeriesType type,
            String title,
            String posterUrl,
            String backdropPath,
            Integer year,
            List<String> genres,
            Double tmdbVoteAverage,
            Double popularity,
            Integer runtimeMinutes,
            Integer numberOfSeasons,
            ContentCardViewerStateDTO viewerState,
            ContentPreviewStatus previewStatus) {
        this(tmdbId, type, title, posterUrl, backdropPath, year, null, genres, tmdbVoteAverage, null,
                popularity, runtimeMinutes, numberOfSeasons, viewerState, previewStatus);
    }

    public TrendingCardDTO(
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
        this(tmdbId, type, title, posterUrl, null, year, null, genres, tmdbVoteAverage, null, popularity,
                runtimeMinutes, numberOfSeasons, viewerState, previewStatus);
    }
}
