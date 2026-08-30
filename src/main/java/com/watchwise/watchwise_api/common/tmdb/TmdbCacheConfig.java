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

@Configuration
@EnableCaching
public class TmdbCacheConfig {

    static final List<String> TMDB_DETAILS_CACHE_NAMES = List.of(
            "tmdbMovieFullDetails", "tmdbTvFullDetails", "tmdbSeasonFullDetails", "tmdbEpisodeFullDetails");

    @Bean
    public CacheManager cacheManager(@Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(TMDB_DETAILS_CACHE_NAMES);
        cacheManager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(ttlHours)));
        return cacheManager;
    }
}
