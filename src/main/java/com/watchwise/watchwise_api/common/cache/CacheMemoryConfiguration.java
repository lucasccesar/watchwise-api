package com.watchwise.watchwise_api.common.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheMemoryProperties.class)
public class CacheMemoryConfiguration {
    @Bean
    CacheBudgetAllocator cacheBudgetAllocator() {
        return new CacheBudgetAllocator();
    }

    @Bean
    Map<String, Long> allocatedCacheMemoryBudgets(CacheMemoryProperties properties,
                                                  CacheBudgetAllocator allocator) {
        return allocator.allocate(properties);
    }
}
