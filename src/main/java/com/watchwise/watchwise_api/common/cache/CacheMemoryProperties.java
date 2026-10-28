package com.watchwise.watchwise_api.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

@ConfigurationProperties(prefix = "app.cache.memory")
public record CacheMemoryProperties(
        @DefaultValue("entries") Mode mode,
        Long maxEstimatedBytes,
        @DefaultValue("52428800") long mediaMaxEstimatedBytes,
        Map<String, Integer> shares) {

    public CacheMemoryProperties {
        if (mode == null) mode = Mode.ENTRIES;
        shares = shares == null ? Map.of() : Map.copyOf(shares);
    }

    public enum Mode {
        ENTRIES,
        WEIGHTED,
        DISABLED
    }
}
