package com.watchwise.watchwise_api.common.tmdb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbCacheConfigTest {

    @Test
    void appliesConfiguredMaximumSizeToDetailsCache() {
        var cache = new TmdbCacheConfig().tmdbMovieFullDetailsCache(24, 2);
        var notFound = new TmdbLookupResult.NotFound<TmdbMovieFullDetails>();

        cache.put("movie-1", notFound);
        cache.put("movie-2", notFound);
        cache.put("movie-3", notFound);
        cache.cleanUp();

        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(2);
    }
}
