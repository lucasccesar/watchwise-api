package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

import java.util.ArrayList;
import java.util.List;

import static com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.MOVIE;
import static com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.SERIES;

public final class ContentSearchPagePlanner {

    public Plan plan(long movieTotal, long seriesTotal, int page, int size) {
        long totalResults = movieTotal + seriesTotal;
        int totalPages = totalResults == 0
                ? 0
                : Math.toIntExact(Math.min(
                        (totalResults - 1) / size + 1,
                        SearchViewPagination.MAX_PAGE));
        long firstGlobalIndex = (long) (page - 1) * size;
        long endExclusive = Math.min(totalResults, firstGlobalIndex + size);
        List<Slot> slots = new ArrayList<>();
        for (long globalIndex = firstGlobalIndex; globalIndex < endExclusive; globalIndex++) {
            slots.add(sourceAt(globalIndex, movieTotal, seriesTotal));
        }
        return new Plan(totalResults, totalPages, page < totalPages, slots);
    }

    private static Slot sourceAt(long globalIndex, long movieTotal, long seriesTotal) {
        long paired = Math.min(movieTotal, seriesTotal);
        if (globalIndex < paired * 2) {
            return globalIndex % 2 == 0
                    ? new Slot(MOVIE, globalIndex / 2)
                    : new Slot(SERIES, globalIndex / 2);
        }
        if (movieTotal > seriesTotal) {
            return new Slot(MOVIE, globalIndex - seriesTotal);
        }
        return new Slot(SERIES, globalIndex - movieTotal);
    }

    public record Plan(long totalResults, int totalPages, boolean hasNext, List<Slot> slots) {
        public Plan {
            slots = List.copyOf(slots);
        }
    }

    public record Slot(MovieOrSeriesType type, long sourceIndex) {
    }
}
