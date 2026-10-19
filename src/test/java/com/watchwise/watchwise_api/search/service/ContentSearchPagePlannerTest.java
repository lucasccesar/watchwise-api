package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.service.ContentSearchPagePlanner.Plan;
import com.watchwise.watchwise_api.search.service.ContentSearchPagePlanner.Slot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSearchPagePlannerTest {

    private final ContentSearchPagePlanner planner = new ContentSearchPagePlanner();

    @Test
    void shouldAlternateMovieAndSeriesSlotsWhenBothSourcesHaveResults() {
        Plan plan = planner.plan(5, 3, 1, 6);

        assertThat(plan)
                .extracting(Plan::totalResults, Plan::totalPages, Plan::hasNext)
                .containsExactly(8L, 2, true);
        assertThat(plan.slots()).containsExactly(
                new Slot(MovieOrSeriesType.MOVIE, 0),
                new Slot(MovieOrSeriesType.SERIES, 0),
                new Slot(MovieOrSeriesType.MOVIE, 1),
                new Slot(MovieOrSeriesType.SERIES, 1),
                new Slot(MovieOrSeriesType.MOVIE, 2),
                new Slot(MovieOrSeriesType.SERIES, 2));
    }

    @Test
    void shouldContinueWithMoviesAfterSeriesSourceIsExhausted() {
        assertThat(planner.plan(5, 2, 2, 4).slots()).containsExactly(
                new Slot(MovieOrSeriesType.MOVIE, 2),
                new Slot(MovieOrSeriesType.MOVIE, 3),
                new Slot(MovieOrSeriesType.MOVIE, 4));
    }

    @Test
    void shouldReturnEmptyMetadataForEmptySources() {
        assertThat(planner.plan(0, 0, 1, 18))
                .extracting(Plan::totalResults, Plan::totalPages, Plan::hasNext)
                .containsExactly(0L, 0, false);
    }

    @Test
    void shouldContinueWithSeriesAfterMovieSourceIsExhausted() {
        assertThat(planner.plan(2, 5, 2, 4).slots()).containsExactly(
                new Slot(MovieOrSeriesType.SERIES, 2),
                new Slot(MovieOrSeriesType.SERIES, 3),
                new Slot(MovieOrSeriesType.SERIES, 4));
    }

    @Test
    void shouldReturnEmptySlotsWhenPageIsBeyondTheEnd() {
        assertThat(planner.plan(5, 3, 3, 6))
                .extracting(Plan::totalResults, Plan::totalPages, Plan::hasNext, Plan::slots)
                .containsExactly(8L, 2, false, List.of());
    }
}
