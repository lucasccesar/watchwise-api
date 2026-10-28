package com.watchwise.watchwise_api.common.cache;

import java.util.Objects;
import java.util.OptionalLong;

public record CacheSnapshot(
        String name,
        CacheMemoryProperties.Mode mode,
        OptionalLong maximumWeight,
        long entries,
        OptionalLong estimatedRetainedWeight,
        long hits,
        long misses,
        long evictions) {

    public CacheSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(maximumWeight, "maximumWeight");
        Objects.requireNonNull(estimatedRetainedWeight, "estimatedRetainedWeight");
    }
}
