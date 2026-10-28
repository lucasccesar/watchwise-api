package com.watchwise.watchwise_api.common.cache;

import java.util.Map;

public final class TestCacheFactory {
    private TestCacheFactory() {}

    public static BudgetedCacheFactory entries() {
        return create(new CacheMemoryProperties(CacheMemoryProperties.Mode.ENTRIES, null,
                50L * 1024 * 1024, Map.of()));
    }

    public static BudgetedCacheFactory disabled() {
        return create(new CacheMemoryProperties(CacheMemoryProperties.Mode.DISABLED, null,
                50L * 1024 * 1024, Map.of()));
    }

    public static BudgetedCacheFactory weighted(long total, long media) {
        return create(new CacheMemoryProperties(CacheMemoryProperties.Mode.WEIGHTED, total, media, Map.of()));
    }

    public static BudgetedCacheFactory weighted(long total, long media, CacheDiagnostics diagnostics) {
        return new BudgetedCacheFactory(new CacheMemoryProperties(CacheMemoryProperties.Mode.WEIGHTED,
                total, media, Map.of()), new CacheBudgetAllocator(), new CacheEntryWeigher(), diagnostics);
    }

    private static BudgetedCacheFactory create(CacheMemoryProperties properties) {
        return new BudgetedCacheFactory(properties, new CacheBudgetAllocator(), new CacheEntryWeigher(),
                new CacheDiagnostics());
    }
}
