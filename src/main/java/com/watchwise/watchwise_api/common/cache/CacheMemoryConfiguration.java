package com.watchwise.watchwise_api.common.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheMemoryProperties.class)
public class CacheMemoryConfiguration {
    @Bean
    CacheEntryWeigher cacheEntryWeigher() {
        return new CacheEntryWeigher();
    }

    @Bean
    CacheDiagnostics cacheDiagnostics() {
        return new CacheDiagnostics();
    }

    @Bean
    CacheBudgetAllocator cacheBudgetAllocator() {
        return new CacheBudgetAllocator();
    }

    @Bean
    Map<String, Long> allocatedCacheMemoryBudgets(CacheMemoryProperties properties,
                                                  CacheBudgetAllocator allocator) {
        return allocator.allocate(properties);
    }

    @Bean
    BudgetedCacheFactory budgetedCacheFactory(CacheMemoryProperties properties, CacheBudgetAllocator allocator,
                                               CacheEntryWeigher weigher, CacheDiagnostics diagnostics) {
        return new BudgetedCacheFactory(properties, allocator, weigher, diagnostics);
    }
}
