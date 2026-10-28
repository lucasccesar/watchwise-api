package com.watchwise.watchwise_api.common.tmdb;

import com.github.benmanes.caffeine.cache.Cache;
import com.watchwise.watchwise_api.common.cache.BudgetedCacheFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class TmdbCacheConfig {

    private final BudgetedCacheFactory cacheFactory;

    public TmdbCacheConfig(BudgetedCacheFactory cacheFactory) {
        this.cacheFactory = cacheFactory;
    }

    private static final int SEASON_FETCH_THREAD_POOL_SIZE = 8;
    private static final int CALENDAR_SCHEDULE_REFRESH_THREAD_POOL_SIZE = 4;
    private static final int CONTENT_RELEASE_DATE_SNAPSHOT_REFRESH_THREAD_POOL_SIZE = 4;
    private static final int CONTENT_RELEASE_DATE_SNAPSHOT_RESOLUTION_THREAD_POOL_SIZE = 4;
    private static final int HOME_NEXT_EPISODE_THREAD_POOL_SIZE = 4;
    private static final int CONTENT_CARD_METADATA_RESOLUTION_THREAD_POOL_SIZE = 8;
    private static final int CONTENT_CARD_METADATA_RESOLUTION_QUEUE_CAPACITY = 50;
    private static final int EPISODE_RATINGS_METADATA_THREAD_POOL_SIZE = 4;
    private static final int EPISODE_RATINGS_METADATA_QUEUE_SIZE = 100;

    @Bean
    public Cache<String, TmdbLookupResult<TmdbMovieFullDetails>> tmdbMovieFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbMovieFullDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbCardMetadata>> tmdbCardMetadataCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbCardMetadataCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbTvFullDetails>> tmdbTvFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbTvFullDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbTvDisplayDetails>> tmdbTvDisplayDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbTvDisplayDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> tmdbSeasonFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbSeasonFullDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbEpisodeFullDetails>> tmdbEpisodeFullDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbEpisodeFullDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbEpisodeImages>> tmdbEpisodeImagesCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.details-cache-maximum-size:10000}") long maximumSize) {
        return cacheFactory.metadata("tmdbEpisodeImagesCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbPersonAggregate>> tmdbPersonAggregateCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.person-aggregate-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbPersonAggregateCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbPersonDetails>> tmdbPersonDetailsCache(
            @Value("${app.tmdb.details-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.person-aggregate-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbPersonDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbMovieReleaseDates>> tmdbMovieReleaseDatesCache(
            @Value("${app.tmdb.calendar-schedule-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.calendar-schedule-cache-maximum-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbMovieReleaseDatesCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> tmdbCalendarSeasonDetailsCache(
            @Value("${app.tmdb.calendar-schedule-cache-ttl-hours}") long ttlHours,
            @Value("${app.tmdb.calendar-schedule-cache-maximum-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbCalendarSeasonDetailsCache", Duration.ofHours(ttlHours), maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> tmdbMovieSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbMovieSearchCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>> tmdbTvSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbTvSearchCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbPersonSearchResult>>> tmdbPersonSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbPersonSearchCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<TmdbSearchCacheKey, TmdbLookupResult<TmdbSearchPage<TmdbMultiSearchResult>>> tmdbMultiSearchCache(
            @Value("${app.tmdb.search-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.search-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbMultiSearchCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSearchPage<TmdbMovieSearchResult>>> tmdbMovieDiscoveryCache(
            @Value("${app.tmdb.discovery-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.discovery-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbMovieDiscoveryCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSearchPage<TmdbTvSearchResult>>> tmdbTvDiscoveryCache(
            @Value("${app.tmdb.discovery-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.discovery-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbTvDiscoveryCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSearchPage<TmdbTrendingMovieResult>>> tmdbTrendingMovieCache(
            @Value("${app.tmdb.trending-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.trending-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbTrendingMovieCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbSearchPage<TmdbTrendingTvResult>>> tmdbTrendingTvCache(
            @Value("${app.tmdb.trending-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.trending-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbTrendingTvCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public Cache<String, TmdbLookupResult<TmdbTvContentRatings>> tmdbTvContentRatingsCache(
            @Value("${app.tmdb.tv-content-ratings-cache-ttl-minutes}") long ttlMinutes,
            @Value("${app.tmdb.tv-content-ratings-cache-max-size}") long maximumSize) {
        return cacheFactory.metadata("tmdbTvContentRatingsCache", Duration.ofMinutes(ttlMinutes), maximumSize);
    }

    @Bean
    public ExecutorService tmdbSeasonFetchExecutor() {
        return Executors.newFixedThreadPool(SEASON_FETCH_THREAD_POOL_SIZE);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService calendarScheduleRefreshExecutor() {
        return Executors.newFixedThreadPool(CALENDAR_SCHEDULE_REFRESH_THREAD_POOL_SIZE);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService contentReleaseDateSnapshotExecutor() {
        return Executors.newFixedThreadPool(CONTENT_RELEASE_DATE_SNAPSHOT_REFRESH_THREAD_POOL_SIZE);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService contentReleaseDateSnapshotResolutionExecutor() {
        return Executors.newFixedThreadPool(CONTENT_RELEASE_DATE_SNAPSHOT_RESOLUTION_THREAD_POOL_SIZE);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService homeNextEpisodeExecutor() {
        return Executors.newFixedThreadPool(HOME_NEXT_EPISODE_THREAD_POOL_SIZE);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService contentCardMetadataResolutionExecutor() {
        return new ThreadPoolExecutor(
                CONTENT_CARD_METADATA_RESOLUTION_THREAD_POOL_SIZE,
                CONTENT_CARD_METADATA_RESOLUTION_THREAD_POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONTENT_CARD_METADATA_RESOLUTION_QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService episodeRatingsMetadataExecutor() {
        return new ThreadPoolExecutor(
                EPISODE_RATINGS_METADATA_THREAD_POOL_SIZE,
                EPISODE_RATINGS_METADATA_THREAD_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EPISODE_RATINGS_METADATA_QUEUE_SIZE),
                (task, executor) -> {
                    try {
                        while (!executor.isShutdown()) {
                            if (executor.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) {
                                return;
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException(
                                "Interrupted while waiting to submit episode ratings metadata", interrupted);
                    }
                    throw new RejectedExecutionException("Episode ratings metadata executor is shut down");
                });
    }
}
