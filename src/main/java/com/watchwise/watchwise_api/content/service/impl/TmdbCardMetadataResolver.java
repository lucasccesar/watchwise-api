package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadataKey;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TmdbCardMetadataResolver {

    private final TmdbClient tmdbClient;

    public TmdbLookupResult<TmdbCardMetadata> resolve(ContentCoordinate coordinate, String language) {
        Objects.requireNonNull(coordinate, "coordinate is required");
        TmdbCardMetadataKey.Type type = switch (coordinate.type()) {
            case MOVIE -> TmdbCardMetadataKey.Type.MOVIE;
            case SERIES -> TmdbCardMetadataKey.Type.SERIES;
            case SEASON -> TmdbCardMetadataKey.Type.SEASON;
            case EPISODE -> TmdbCardMetadataKey.Type.EPISODE;
        };
        return tmdbClient.getCardMetadata(new TmdbCardMetadataKey(
                type,
                coordinate.tmdbId(),
                coordinate.seriesTmdbId(),
                coordinate.seasonNumber(),
                coordinate.episodeNumber()), language);
    }
}
