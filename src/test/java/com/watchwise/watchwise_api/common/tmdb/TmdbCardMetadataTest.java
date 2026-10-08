package com.watchwise.watchwise_api.common.tmdb;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.TmdbCardMetadataResolver;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TmdbCardMetadataTest {

    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final TmdbCardMetadataResolver resolver = new TmdbCardMetadataResolver(tmdbClient);

    @Test
    void resolvesMovieCoordinateThroughMovieMetadataKey() {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, "603", null, null, null);
        TmdbCardMetadataKey key = new TmdbCardMetadataKey(
                TmdbCardMetadataKey.Type.MOVIE, "603", null, null, null);
        when(tmdbClient.getCardMetadata(key, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbCardMetadata("The Matrix", "/matrix.jpg",
                        "1999-03-31", 136)));

        resolver.resolve(coordinate, "en-US");

        verify(tmdbClient).getCardMetadata(key, "en-US");
    }

    @Test
    void resolvesEpisodeCoordinateWithoutAStandaloneTmdbId() {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.EPISODE, null, "1396", 2, 4);
        TmdbCardMetadataKey key = new TmdbCardMetadataKey(
                TmdbCardMetadataKey.Type.EPISODE, null, "1396", 2, 4);
        when(tmdbClient.getCardMetadata(key, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbCardMetadata("Episode 4", "/still.jpg",
                        "2009-03-29", 47)));

        resolver.resolve(coordinate, "en-US");

        verify(tmdbClient).getCardMetadata(key, "en-US");
    }
}
