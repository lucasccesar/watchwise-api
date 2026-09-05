package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class TmdbCacheConfig {

    private static final int SEASON_FETCH_THREAD_POOL_SIZE = 8;

    @Bean
    public Cache<String, TmdbLookupResult<TmdbMovieFullDetails>> tmdbMovieFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours) {
        return newCache(ttlHours);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbTvFullDetails>> tmdbTvFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours) {
        return newCache(ttlHours);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> tmdbSeasonFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours) {
        return newCache(ttlHours);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbEpisodeFullDetails>> tmdbEpisodeFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours) {
        return newCache(ttlHours);
    }

    private <T> Cache<String, T> newCache(long ttlHours) {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(ttlHours)).build();
    }

    @Bean
    public ExecutorService tmdbSeasonFetchExecutor() {
        return Executors.newFixedThreadPool(SEASON_FETCH_THREAD_POOL_SIZE);
    }
}
