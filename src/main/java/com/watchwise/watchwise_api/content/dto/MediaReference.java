package com.watchwise.watchwise_api.content.dto;

import com.watchwise.watchwise_api.content.entity.ContentType;

public record MediaReference(
        ContentType type,
        String tmdbId,
        String seriesTmdbId,
        Integer seasonNumber,
        Integer episodeNumber) {

    public MediaReference {
        if (type == null) {
            throw new IllegalArgumentException("Content type is required");
        }
        boolean valid = switch (type) {
            case MOVIE, SERIES -> present(tmdbId) && !present(seriesTmdbId)
                    && seasonNumber == null && episodeNumber == null;
            case SEASON -> !present(tmdbId) && present(seriesTmdbId)
                    && positive(seasonNumber) && episodeNumber == null;
            case EPISODE -> !present(tmdbId) && present(seriesTmdbId)
                    && positive(seasonNumber) && positive(episodeNumber);
        };
        if (!valid) {
            throw new IllegalArgumentException("Media reference identifiers do not match content type");
        }
    }

    public static MediaReference movie(String id) {
        return new MediaReference(ContentType.MOVIE, id, null, null, null);
    }

    public static MediaReference series(String id) {
        return new MediaReference(ContentType.SERIES, id, null, null, null);
    }

    public static MediaReference season(String seriesId, int number) {
        return new MediaReference(ContentType.SEASON, null, seriesId, number, null);
    }

    public static MediaReference episode(String seriesId, int season, int episode) {
        return new MediaReference(ContentType.EPISODE, null, seriesId, season, episode);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
