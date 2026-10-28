package com.watchwise.watchwise_api.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** In-process, metadata-only cache diagnostics. It never iterates cache keys or values. */
public final class CacheDiagnostics {
    private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();

    void register(String name, CacheMemoryProperties.Mode mode, OptionalLong maximumWeight,
                  boolean reportRetainedWeight, Cache<?, ?> cache) {
        Registration registration = new Registration(name, mode, maximumWeight, reportRetainedWeight, cache);
        if (registrations.putIfAbsent(name, registration) != null) {
            throw new IllegalStateException("Duplicate cache diagnostic name");
        }
    }

    public List<CacheSnapshot> snapshot() {
        List<Registration> current = new ArrayList<>(registrations.values());
        current.sort(Comparator.comparing(Registration::name));
        return current.stream().map(this::snapshot).toList();
    }

    private CacheSnapshot snapshot(Registration registration) {
        var cache = registration.cache();
        var stats = cache.stats();
        OptionalLong retainedWeight = OptionalLong.empty();
        if (registration.reportRetainedWeight()) {
            var eviction = cache.policy().eviction();
            if (eviction.isPresent()) retainedWeight = eviction.get().weightedSize();
        }
        return new CacheSnapshot(registration.name(), registration.mode(), registration.maximumWeight(),
                cache.estimatedSize(), retainedWeight, stats.hitCount(), stats.missCount(), stats.evictionCount());
    }

    private record Registration(String name, CacheMemoryProperties.Mode mode, OptionalLong maximumWeight,
                                boolean reportRetainedWeight, Cache<?, ?> cache) {}
}
