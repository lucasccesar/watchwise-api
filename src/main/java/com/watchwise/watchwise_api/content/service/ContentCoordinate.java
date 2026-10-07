package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.Content;

public record ContentCoordinate(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {

    public static ContentCoordinate from(Content content) {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        return new ContentCoordinate(
                content.getType(),
                content.getTmdbId(),
                content.getSeriesTmdbId(),
                content.getSeasonNumber(),
                content.getEpisodeNumber());
    }
}
