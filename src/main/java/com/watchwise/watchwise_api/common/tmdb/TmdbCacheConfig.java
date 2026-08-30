package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableCaching
public class TmdbCacheConfig {

    static final List<String> TMDB_DETAILS_CACHE_NAMES = List.of(
            "tmdbMovieFullDetails", "tmdbTvFullDetails", "tmdbSeasonFullDetails", "tmdbEpisodeFullDetails");

    private static final int SEASON_FETCH_THREAD_POOL_SIZE = 8;

    @Bean
    public CacheManager cacheManager(@Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(TMDB_DETAILS_CACHE_NAMES);
        cacheManager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(ttlHours)));
        return cacheManager;
    }

    @Bean
    public ExecutorService tmdbSeasonFetchExecutor() {
        return Executors.newFixedThreadPool(SEASON_FETCH_THREAD_POOL_SIZE);
    }
}
