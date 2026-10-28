package com.watchwise.watchwise_api.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Creates the application's metadata and image caches under one selected memory policy. */
public final class BudgetedCacheFactory {
    private final CacheMemoryProperties properties;
    private final Map<String, Long> weightedBudgets;
    private final CacheEntryWeigher entryWeigher;
    private final CacheDiagnostics diagnostics;

    public BudgetedCacheFactory(CacheMemoryProperties properties, CacheBudgetAllocator allocator,
                                CacheEntryWeigher entryWeigher, CacheDiagnostics diagnostics) {
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(allocator, "allocator");
        this.weightedBudgets = allocator.allocate(properties);
        this.entryWeigher = Objects.requireNonNull(entryWeigher, "entryWeigher");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public <K, V> Cache<K, V> metadata(String name, Duration ttl, long legacyMaximumEntries) {
        requireCacheName(name);
        requireTtl(ttl);
        if (legacyMaximumEntries < 0) throw new IllegalArgumentException("Legacy cache size cannot be negative");

        Cache<K, V> cache;
        OptionalLong maximumWeight = OptionalLong.empty();
        boolean reportRetainedWeight = false;
        switch (properties.mode()) {
            case ENTRIES -> cache = Caffeine.<K, V>newBuilder()
                    .maximumSize(legacyMaximumEntries)
                    .expireAfterWrite(ttl)
                    .recordStats()
                    .build();
            case DISABLED -> cache = Caffeine.<K, V>newBuilder()
                    .maximumSize(0)
                    .expireAfterWrite(ttl)
                    .recordStats()
                    .build();
            case WEIGHTED -> {
                long budget = requireWeightedBudget(name);
                cache = Caffeine.<K, V>newBuilder()
                        .maximumWeight(budget)
                        .weigher((K key, V value) -> entryWeigher.weight(key, value, budget))
                        .expireAfterWrite(ttl)
                        .recordStats()
                        .build();
                maximumWeight = OptionalLong.of(budget);
                reportRetainedWeight = true;
            }
            default -> throw new IllegalStateException("Unsupported cache memory mode");
        }
        diagnostics.register(name, properties.mode(), maximumWeight, reportRetainedWeight, cache);
        return cache;
    }

    public <K, V> Cache<K, V> media(Duration ttl, long legacyMaximumBytes, Weigher<K, V> legacyWeigher) {
        requireTtl(ttl);
        if (legacyMaximumBytes < 0) throw new IllegalArgumentException("Legacy media cache size cannot be negative");
        Objects.requireNonNull(legacyWeigher, "legacyWeigher");
        String name = CacheBudgetAllocator.MEDIA_CACHE;

        Cache<K, V> cache;
        OptionalLong maximumWeight = OptionalLong.empty();
        boolean reportRetainedWeight = false;
        switch (properties.mode()) {
            case ENTRIES -> {
                cache = Caffeine.<K, V>newBuilder()
                        .maximumWeight(legacyMaximumBytes)
                        .weigher(legacyWeigher)
                        .expireAfterWrite(ttl)
                        .recordStats()
                        .build();
                maximumWeight = OptionalLong.of(legacyMaximumBytes);
                reportRetainedWeight = true;
            }
            case DISABLED -> cache = Caffeine.<K, V>newBuilder()
                    .maximumSize(0)
                    .expireAfterWrite(ttl)
                    .recordStats()
                    .build();
            case WEIGHTED -> {
                long budget = requireWeightedBudget(name);
                cache = Caffeine.<K, V>newBuilder()
                        .maximumWeight(budget)
                        .weigher((K key, V value) -> entryWeigher.weight(key, value, budget))
                        .expireAfterWrite(ttl)
                        .recordStats()
                        .build();
                maximumWeight = OptionalLong.of(budget);
                reportRetainedWeight = true;
            }
            default -> throw new IllegalStateException("Unsupported cache memory mode");
        }
        diagnostics.register(name, properties.mode(), maximumWeight, reportRetainedWeight, cache);
        return cache;
    }

    private long requireWeightedBudget(String name) {
        Long budget = weightedBudgets.get(name);
        if (budget == null || budget <= 0 || budget >= Integer.MAX_VALUE) {
            throw new IllegalStateException("Weighted cache budget is missing or invalid");
        }
        return budget;
    }

    private static void requireCacheName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Cache name is required");
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative()) throw new IllegalArgumentException("Cache TTL cannot be negative");
    }
}
