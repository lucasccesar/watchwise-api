package com.watchwise.watchwise_api.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.watchwise.watchwise_api.common.tmdb.TmdbCacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInventoryIntegrationTest {
    @Test
    void weightedModeRegistersAllTwentyTmdbCachesAndTheImageCacheWithinOneBudget() {
        try (var context = context(CacheMemoryProperties.Mode.WEIGHTED, 1_000_000L)) {
            Map<String, Cache> caches = context.getBeansOfType(Cache.class);
            assertThat(caches).hasSize(20);
            assertThat(caches.keySet()).containsExactlyInAnyOrderElementsOf(
                    CacheBudgetAllocator.metadataCacheNames());

            context.getBean(BudgetedCacheFactory.class).media(Duration.ofMinutes(10), 50L * 1024 * 1024,
                    (String key, String value) -> value.length());

            var snapshots = context.getBean(CacheDiagnostics.class).snapshot();
            assertThat(snapshots).hasSize(21);
            assertThat(snapshots).extracting(CacheSnapshot::name)
                    .contains(CacheBudgetAllocator.MEDIA_CACHE)
                    .containsAll(CacheBudgetAllocator.metadataCacheNames());
            assertThat(snapshots).allSatisfy(snapshot -> {
                assertThat(snapshot.mode()).isEqualTo(CacheMemoryProperties.Mode.WEIGHTED);
                assertThat(snapshot.maximumWeight()).isPresent();
            });
            assertThat(snapshots.stream().mapToLong(snapshot -> snapshot.maximumWeight().orElseThrow()).sum())
                    .isLessThanOrEqualTo(1_000_000L);
            assertThat(snapshots.stream().mapToLong(snapshot -> snapshot.maximumWeight().orElseThrow()).sum())
                    .isEqualTo(1_000_000L);
        }
    }

    @Test
    void disabledModeRegistersAllCachesWithoutRequiringABudget() {
        try (var context = context(CacheMemoryProperties.Mode.DISABLED, null)) {
            context.getBean(BudgetedCacheFactory.class).media(Duration.ofMinutes(10), 50L * 1024 * 1024,
                    (String key, String value) -> value.length());

            var snapshots = context.getBean(CacheDiagnostics.class).snapshot();
            assertThat(snapshots).hasSize(21);
            assertThat(snapshots).allSatisfy(snapshot -> {
                assertThat(snapshot.mode()).isEqualTo(CacheMemoryProperties.Mode.DISABLED);
                assertThat(snapshot.entries()).isZero();
            });
        }
    }

    private static AnnotationConfigApplicationContext context(CacheMemoryProperties.Mode mode, Long total) {
        var context = new AnnotationConfigApplicationContext();
        var placeholders = new PropertySourcesPlaceholderConfigurer();
        placeholders.setEnvironment(context.getEnvironment());
        context.addBeanFactoryPostProcessor(placeholders);
        Map<String, Object> properties = new HashMap<>();
        properties.put("app.tmdb.details-cache-ttl-hours", 24);
        properties.put("app.tmdb.details-cache-maximum-size", 10_000);
        properties.put("app.tmdb.person-aggregate-cache-max-size", 10_000);
        properties.put("app.tmdb.calendar-schedule-cache-ttl-hours", 24);
        properties.put("app.tmdb.calendar-schedule-cache-maximum-size", 10_000);
        properties.put("app.tmdb.search-cache-ttl-minutes", 10);
        properties.put("app.tmdb.search-cache-max-size", 10_000);
        properties.put("app.tmdb.discovery-cache-ttl-minutes", 10);
        properties.put("app.tmdb.discovery-cache-max-size", 10_000);
        properties.put("app.tmdb.trending-cache-ttl-minutes", 10);
        properties.put("app.tmdb.trending-cache-max-size", 10_000);
        properties.put("app.tmdb.tv-content-ratings-cache-ttl-minutes", 60);
        properties.put("app.tmdb.tv-content-ratings-cache-max-size", 10_000);
        properties.put("app.cache.memory.mode", mode.name().toLowerCase());
        if (total != null) properties.put("app.cache.memory.max-estimated-bytes", total);
        properties.put("app.cache.memory.media-max-estimated-bytes", 250_000L);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cache-inventory-test", properties));
        context.register(CacheMemoryConfiguration.class, TmdbCacheConfig.class);
        context.refresh();
        return context;
    }
}
