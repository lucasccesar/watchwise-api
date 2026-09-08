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

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> tmdbMovieSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return newSearchCache(ttlMinutes, maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>> tmdbTvSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return newSearchCache(ttlMinutes, maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>>> tmdbPersonSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return newSearchCache(ttlMinutes, maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMultiSearchResult>>> tmdbMultiSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return newSearchCache(ttlMinutes, maximumSize);
    }

    private <T> Cache<TmdbSearchCacheKey, TmdbLookupResult<T>> newSearchCache(long ttlMinutes, long maximumSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .maximumSize(maximumSize)
                .build();
    }

    private <T> Cache<String, T> newCache(long ttlHours) {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(ttlHours)).build();
    }

    @Bean
    public ExecutorService tmdbSeasonFetchExecutor() {
        return Executors.newFixedThreadPool(SEASON_FETCH_THREAD_POOL_SIZE);
    }
}
