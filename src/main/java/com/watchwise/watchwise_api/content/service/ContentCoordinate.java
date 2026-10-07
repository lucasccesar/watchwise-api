package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record ContentCoordinate(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {
}
