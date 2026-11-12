package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentImageDTO;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

import java.util.List;

public interface ContentImagesService {

    List<ContentImageDTO> listPosters(MovieOrSeriesType type, String tmdbId);

    default List<ContentImageDTO> listPosters(
            MovieOrSeriesType type, String tmdbId, String includeImageLanguage) {
        return listPosters(type, tmdbId);
    }

    List<ContentImageDTO> listBackdrops(MovieOrSeriesType type, String tmdbId);

    default List<ContentImageDTO> listBackdrops(
            MovieOrSeriesType type, String tmdbId, String includeImageLanguage) {
        return listBackdrops(type, tmdbId);
    }
}
