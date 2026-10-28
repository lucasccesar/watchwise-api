package com.watchwise.watchwise_api.common.cache;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CacheBudgetAllocator {
    public static final String MEDIA_CACHE = "dailyGameMedia";
    private static final long MAX_CACHE_WEIGHT = Integer.MAX_VALUE;
    private static final List<String> METADATA_CACHES = List.of(
            "tmdbMovieFullDetailsCache",
            "tmdbCardMetadataCache",
            "tmdbTvFullDetailsCache",
            "tmdbTvDisplayDetailsCache",
            "tmdbSeasonFullDetailsCache",
            "tmdbEpisodeFullDetailsCache",
            "tmdbEpisodeImagesCache",
            "tmdbPersonAggregateCache",
            "tmdbPersonDetailsCache",
            "tmdbMovieReleaseDatesCache",
            "tmdbCalendarSeasonDetailsCache",
            "tmdbMovieSearchCache",
            "tmdbTvSearchCache",
            "tmdbPersonSearchCache",
            "tmdbMultiSearchCache",
            "tmdbMovieDiscoveryCache",
            "tmdbTvDiscoveryCache",
            "tmdbTrendingMovieCache",
            "tmdbTrendingTvCache",
            "tmdbTvContentRatingsCache");
    private static final Map<String, String> NORMALIZED_CACHE_NAMES = normalizedCacheNames();

    public Map<String, Long> allocate(CacheMemoryProperties properties) {
        if (properties == null) throw new IllegalArgumentException("Cache memory properties are required");
        if (properties.mode() != CacheMemoryProperties.Mode.WEIGHTED) return Map.of();

        Long totalBytes = properties.maxEstimatedBytes();
        long mediaBytes = properties.mediaMaxEstimatedBytes();
        if (totalBytes == null || totalBytes <= 0 || mediaBytes <= 0 || mediaBytes >= totalBytes) {
            throw new IllegalStateException("Weighted cache memory requires positive total and media budgets");
        }
        requireCacheWeight(mediaBytes);

        Map<String, Integer> shares = canonicalShares(properties.shares());
        long totalShares = 0;
        try {
            for (int share : shares.values()) totalShares = Math.addExact(totalShares, share);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Cache memory shares overflow", exception);
        }
        for (String cacheName : METADATA_CACHES) {
            totalShares = Math.addExact(totalShares, shares.containsKey(cacheName) ? 0 : 1);
        }

        long metadataBytes = totalBytes - mediaBytes;
        Map<String, Long> budgets = new HashMap<>(METADATA_CACHES.size() + 1);
        budgets.put(MEDIA_CACHE, mediaBytes);
        for (String cacheName : METADATA_CACHES) {
            long share = shares.getOrDefault(cacheName, 1);
            long budget = BigInteger.valueOf(metadataBytes).multiply(BigInteger.valueOf(share))
                    .divide(BigInteger.valueOf(totalShares)).longValueExact();
            if (budget <= 0) {
                throw new IllegalStateException("Weighted cache memory leaves a cache with zero budget");
            }
            requireCacheWeight(budget);
            budgets.put(cacheName, budget);
        }
        return Map.copyOf(budgets);
    }

    public static List<String> metadataCacheNames() {
        return METADATA_CACHES;
    }

    private Map<String, Integer> canonicalShares(Map<String, Integer> configuredShares) {
        if (configuredShares == null || configuredShares.isEmpty()) return Map.of();
        Map<String, Integer> result = new HashMap<>();
        configuredShares.forEach((name, share) -> {
            String canonical = NORMALIZED_CACHE_NAMES.get(normalize(name));
            if (canonical == null || MEDIA_CACHE.equals(canonical)) {
                throw new IllegalStateException("Unknown cache memory share name");
            }
            if (share == null || share <= 0) {
                throw new IllegalStateException("Cache memory shares must be positive");
            }
            if (result.putIfAbsent(canonical, share) != null) {
                throw new IllegalStateException("Duplicate cache memory share name");
            }
        });
        return Map.copyOf(result);
    }

    private void requireCacheWeight(long budget) {
        if (budget >= MAX_CACHE_WEIGHT) {
            throw new IllegalStateException("Weighted cache budget must be below Integer.MAX_VALUE");
        }
    }

    private static Map<String, String> normalizedCacheNames() {
        Map<String, String> names = new HashMap<>();
        for (String name : METADATA_CACHES) names.put(normalize(name), name);
        names.put(normalize(MEDIA_CACHE), MEDIA_CACHE);
        return Map.copyOf(names);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace("-", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }
}
