package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

public final class TmdbSearchWindowReader {

    private static final int SOURCE_PAGE_SIZE = 20;

    public <T> Window<T> readPage(
            int visualPage,
            int visualSize,
            IntFunction<TmdbLookupResult<TmdbSearchPage<T>>> loader) {
        validateVisualPage(visualPage);
        validateCount(visualSize, "visualSize");

        long firstSourceIndex = (long) (visualPage - 1) * visualSize;
        Range<T> range = readRange(firstSourceIndex, visualSize, loader);
        int totalPages = totalPages(range.totalResults(), visualSize);

        return new Window<>(range.results(), range.totalResults(), totalPages, visualPage < totalPages);
    }

    public <T> Range<T> readRange(
            long firstSourceIndex,
            int count,
            IntFunction<TmdbLookupResult<TmdbSearchPage<T>>> loader) {
        if (firstSourceIndex < 0) {
            throw new IllegalArgumentException("firstSourceIndex must not be negative");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        Objects.requireNonNull(loader, "loader");

        Map<Integer, TmdbSearchPage<T>> pages = new HashMap<>();
        TmdbSearchPage<T> firstPage = loadPage(1, loader);
        pages.put(1, firstPage);
        long totalResults = Math.max(0, firstPage.totalResults());

        if (count == 0 || firstSourceIndex >= totalResults) {
            return new Range<>(Map.of(), totalResults);
        }

        long requestedEnd = firstSourceIndex > Long.MAX_VALUE - count
                ? Long.MAX_VALUE
                : firstSourceIndex + count;
        long endExclusive = Math.min(totalResults, requestedEnd);
        long firstSourcePage = sourcePageForIndex(firstSourceIndex);
        long lastSourcePage = sourcePageForIndex(endExclusive - 1);

        for (long sourcePage = firstSourcePage; sourcePage <= lastSourcePage; sourcePage++) {
            int sourcePageNumber = Math.toIntExact(sourcePage);
            pages.computeIfAbsent(sourcePageNumber, ignored -> loadPage(sourcePageNumber, loader));
        }

        Map<Long, T> resultsBySourceIndex = new LinkedHashMap<>();
        for (long sourceIndex = firstSourceIndex; sourceIndex < endExclusive; sourceIndex++) {
            int sourcePageNumber = Math.toIntExact(sourcePageForIndex(sourceIndex));
            TmdbSearchPage<T> page = pages.get(sourcePageNumber);
            List<T> sourceResults = page.results() == null ? List.of() : page.results();
            int resultIndex = (int) (sourceIndex % SOURCE_PAGE_SIZE);
            if (resultIndex < sourceResults.size() && sourceResults.get(resultIndex) != null) {
                resultsBySourceIndex.put(sourceIndex, sourceResults.get(resultIndex));
            }
        }
        return new Range<>(resultsBySourceIndex, totalResults);
    }

    private static <T> TmdbSearchPage<T> loadPage(
            int sourcePage,
            IntFunction<TmdbLookupResult<TmdbSearchPage<T>>> loader) {
        TmdbLookupResult<TmdbSearchPage<T>> lookup = loader.apply(sourcePage);
        if (lookup == null) {
            throw new IllegalStateException("TMDB search loader returned null");
        }
        return switch (lookup) {
            case TmdbLookupResult.Found<TmdbSearchPage<T>> found -> Objects.requireNonNull(
                    found.value(), "TMDB search loader returned a null page");
            case TmdbLookupResult.NotFound<TmdbSearchPage<T>> ignored ->
                    new TmdbSearchPage<>(sourcePage, List.of(), 0, 0);
            case TmdbLookupResult.Unavailable<TmdbSearchPage<T>> ignored ->
                    throw new TmdbUnavailableException("TMDB search is unavailable");
        };
    }

    private static long sourcePageForIndex(long sourceIndex) {
        return sourceIndex / SOURCE_PAGE_SIZE + 1;
    }

    private static int totalPages(long totalResults, int visualSize) {
        if (totalResults == 0) {
            return 0;
        }
        long pages = (totalResults - 1) / visualSize + 1;
        return Math.toIntExact(pages);
    }

    private static void validateVisualPage(int visualPage) {
        if (visualPage < 1) {
            throw new IllegalArgumentException("visualPage must be greater than zero");
        }
    }

    private static void validateCount(int count, String name) {
        if (count < 1) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
    }

    public record Window<T>(List<T> results, long totalResults, int totalPages, boolean hasNext) {
        public Window {
            results = List.copyOf(results);
        }
    }

    public record Range<T>(Map<Long, T> resultsBySourceIndex, long totalResults) {
        public Range {
            resultsBySourceIndex = Collections.unmodifiableMap(new LinkedHashMap<>(resultsBySourceIndex));
        }

        public List<T> results() {
            return List.copyOf(resultsBySourceIndex.values());
        }

        public List<T> items() {
            return results();
        }
    }
}
