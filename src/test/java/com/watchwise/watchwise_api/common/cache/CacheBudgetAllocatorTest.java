package com.watchwise.watchwise_api.common.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheBudgetAllocatorTest {
    @Test
    void partitionsMetadataAndMediaWithoutMultiplyingTheTotal() {
        var properties = new CacheMemoryProperties(CacheMemoryProperties.Mode.WEIGHTED,
                1000L, 200L, Map.of());

        Map<String, Long> budgets = new CacheBudgetAllocator().allocate(properties);

        assertThat(budgets).hasSize(21);
        assertThat(budgets.get(CacheBudgetAllocator.MEDIA_CACHE)).isEqualTo(200L);
        assertThat(budgets.get("tmdbMovieSearchCache")).isEqualTo(40L);
        assertThat(budgets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1000L);
    }

    @Test
    void keepsDivisionRemainderUnallocatedAndHonorsUnequalShares() {
        var properties = new CacheMemoryProperties(CacheMemoryProperties.Mode.WEIGHTED,
                1003L, 201L, Map.of("tmdbMovieSearchCache", 2));

        Map<String, Long> budgets = new CacheBudgetAllocator().allocate(properties);

        assertThat(budgets.get("dailyGameMedia")).isEqualTo(201L);
        assertThat(budgets.get("tmdbMovieSearchCache")).isEqualTo(76L);
        assertThat(budgets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(999L);
    }

    @Test
    void nonWeightedModesNeedNoTotalBudget() {
        var allocator = new CacheBudgetAllocator();
        assertThat(allocator.allocate(new CacheMemoryProperties(CacheMemoryProperties.Mode.ENTRIES,
                null, 50L * 1024 * 1024, Map.of()))).isEmpty();
        assertThat(allocator.allocate(new CacheMemoryProperties(CacheMemoryProperties.Mode.DISABLED,
                null, 50L * 1024 * 1024, Map.of()))).isEmpty();
    }

    @Test
    void rejectsInvalidWeightedBudgetsAndShares() {
        var allocator = new CacheBudgetAllocator();
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, null, 200L, Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 100L, 100L, Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 1000L, 200L, Map.of("unknownCache", 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 1000L, 200L, Map.of("tmdbMovieSearchCache", 0))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 1000L, 200L,
                Map.of("tmdbMovieSearchCache", 1, "tmdb-movie-search-cache", 2))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 3_000_000_000L, 1L,
                Map.of("tmdbMovieSearchCache", 100))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsZeroAllocationsAndReturnsAnImmutableMap() {
        var allocator = new CacheBudgetAllocator();
        assertThatThrownBy(() -> allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 20L, 1L, Map.of())))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Long> budgets = allocator.allocate(new CacheMemoryProperties(
                CacheMemoryProperties.Mode.WEIGHTED, 1000L, 200L, Map.of()));
        assertThatThrownBy(() -> budgets.put("extra", 1L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
