package com.watchwise.watchwise_api.common.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CacheMemoryPropertiesTest {
    @Test
    void defaultsToEntryModeWithTheExistingImageCacheLimit() {
        new ApplicationContextRunner().withUserConfiguration(CachePropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(CacheMemoryProperties.class);
                    assertThat(properties.mode()).isEqualTo(CacheMemoryProperties.Mode.ENTRIES);
                    assertThat(properties.maxEstimatedBytes()).isNull();
                    assertThat(properties.mediaMaxEstimatedBytes()).isEqualTo(50L * 1024 * 1024);
                    assertThat(properties.shares()).isEmpty();
                });
    }

    @Test
    void bindsWeightedModeAndNamedShares() {
        new ApplicationContextRunner().withUserConfiguration(CachePropertiesConfiguration.class)
                .withPropertyValues("app.cache.memory.mode=weighted",
                        "app.cache.memory.max-estimated-bytes=1000",
                        "app.cache.memory.media-max-estimated-bytes=200",
                        "app.cache.memory.shares.tmdbMovieSearchCache=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(CacheMemoryProperties.class);
                    assertThat(properties.mode()).isEqualTo(CacheMemoryProperties.Mode.WEIGHTED);
                    assertThat(properties.maxEstimatedBytes()).isEqualTo(1000L);
                    assertThat(properties.mediaMaxEstimatedBytes()).isEqualTo(200L);
                    assertThat(properties.shares()).containsExactlyInAnyOrderEntriesOf(Map.of("tmdbMovieSearchCache", 2));
                });
    }

    @Test
    void rejectsAnUnknownModeDuringBinding() {
        new ApplicationContextRunner().withUserConfiguration(CachePropertiesConfiguration.class)
                .withPropertyValues("app.cache.memory.mode=unbounded")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CacheMemoryProperties.class)
    static class CachePropertiesConfiguration { }
}
