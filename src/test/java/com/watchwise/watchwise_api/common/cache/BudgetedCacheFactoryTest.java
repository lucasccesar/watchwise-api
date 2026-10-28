package com.watchwise.watchwise_api.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaResponseDTO;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetedCacheFactoryTest {
    @Test
    void weightedCacheDoesNotRetainAnEntryThatExceedsItsOwnShare() {
        var factory = TestCacheFactory.weighted(40_000, 20_000);
        Cache<String, byte[]> cache = factory.metadata("tmdbMovieSearchCache", Duration.ofMinutes(10), 10_000);

        cache.put("too-large", new byte[4_096]);
        cache.cleanUp();

        assertThat(cache.getIfPresent("too-large")).isNull();
    }

    @Test
    void weightedCacheRetainsAnEntryThatFitsItsShare() {
        var factory = TestCacheFactory.weighted(40_000, 20_000);
        Cache<String, String> cache = factory.metadata("tmdbMovieSearchCache", Duration.ofMinutes(10), 10_000);

        cache.put("small", "value");
        cache.cleanUp();

        assertThat(cache.getIfPresent("small")).isEqualTo("value");
    }

    @Test
    void disabledCacheExecutesItsLoaderWithoutKeepingTheResult() {
        Cache<String, String> cache = TestCacheFactory.disabled()
                .metadata("tmdbMovieSearchCache", Duration.ofMinutes(10), 10_000);
        var loads = new AtomicInteger();

        cache.get("key", ignored -> "value-" + loads.incrementAndGet());
        cache.cleanUp();
        cache.get("key", ignored -> "value-" + loads.incrementAndGet());

        assertThat(loads).hasValue(2);
    }

    @Test
    void disabledMediaCacheExecutesItsLoaderWithoutKeepingTheResult() {
        Cache<String, String> cache = TestCacheFactory.disabled().media(Duration.ofMinutes(10),
                50L * 1024 * 1024, (key, value) -> value.length());
        var loads = new AtomicInteger();

        cache.get("image", ignored -> "image-" + loads.incrementAndGet());
        cache.cleanUp();
        cache.get("image", ignored -> "image-" + loads.incrementAndGet());

        assertThat(loads).hasValue(2);
    }

    @Test
    void entriesModePreservesLegacyMaximumSizeAndRecordsStats() {
        var diagnostics = new CacheDiagnostics();
        var factory = new BudgetedCacheFactory(
                new CacheMemoryProperties(CacheMemoryProperties.Mode.ENTRIES, null, 50L * 1024 * 1024, null),
                new CacheBudgetAllocator(), new CacheEntryWeigher(), diagnostics);
        Cache<String, String> cache = factory.metadata("testEntries", Duration.ofMinutes(10), 1);

        cache.put("first", "value");
        cache.put("second", "value");
        cache.cleanUp();
        cache.getIfPresent("second");

        assertThat(cache.estimatedSize()).isEqualTo(1);
        assertThat(diagnostics.snapshot()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.entries()).isEqualTo(1);
            assertThat(snapshot.hits()).isEqualTo(1);
            assertThat(snapshot.maximumWeight()).isEmpty();
        });
    }

    @Test
    void weightedMediaCacheUsesTheStructuredImageSizeAndPrivateApplicationKey() throws Exception {
        var diagnostics = new CacheDiagnostics();
        var factory = TestCacheFactory.weighted(40_000, 20_000, diagnostics);
        Cache<Object, DailyGameMediaResponseDTO> cache = factory.media(Duration.ofMinutes(10), 50L * 1024 * 1024,
                (key, value) -> value.body().length);
        Object key = dailyGameMediaKey();

        cache.put(key, new DailyGameMediaResponseDTO(new byte[128], MediaType.IMAGE_JPEG));
        cache.cleanUp();

        assertThat(cache.getIfPresent(key)).isNotNull();
        assertThat(diagnostics.snapshot()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.name()).isEqualTo(CacheBudgetAllocator.MEDIA_CACHE);
            assertThat(snapshot.estimatedRetainedWeight()).isPresent();
        });
    }

    private static Object dailyGameMediaKey() throws Exception {
        Class<?> keyType = Class.forName(
                "com.watchwise.watchwise_api.dailygame.service.impl.DailyGameMediaServiceImpl$MediaKey");
        Constructor<?> constructor = keyType.getDeclaredConstructor(UUID.class, DailyGameMediaSlot.class,
                DailyGameImageVariant.class, String.class);
        assertThat(constructor.trySetAccessible()).isTrue();
        return constructor.newInstance(UUID.randomUUID(), DailyGameMediaSlot.CLUE,
                DailyGameImageVariant.ORIGINAL, "fingerprint");
    }
}
