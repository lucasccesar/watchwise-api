package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSearchPagePlannerTest {

    private final ContentSearchPagePlanner planner = new ContentSearchPagePlanner();

    @Test
    void shouldInterleaveMovieAndSeriesSourcesWhenBothHaveResults() {
        assertThat(planner.plan(5, 3, 1, 6).slots()).containsExactly(
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 0),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 0),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 1),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 1),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 2),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 2));
    }

    @Test
    void shouldContinueWithExhaustedSeriesSource() {
        assertThat(planner.plan(5, 2, 2, 4).slots()).containsExactly(
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 2),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 3),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 4));
    }

    @Test
    void shouldRetainEmptyCombinedMetadataWhenBothSourcesAreEmpty() {
        assertThat(planner.plan(0, 0, 1, 18))
                .extracting(ContentSearchPagePlanner.Plan::totalResults,
                        ContentSearchPagePlanner.Plan::totalPages,
                        ContentSearchPagePlanner.Plan::hasNext)
                .containsExactly(0L, 0, false);
    }

    @Test
    void shouldContinueWithExhaustedMovieSource() {
        assertThat(planner.plan(2, 5, 2, 4).slots())
                .containsExactly(
                        new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 2),
                        new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 3),
                        new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 4));
    }

    @Test
    void shouldCapReportedPagesAtTheMaximumAcceptedVisualPage() {
        ContentSearchPagePlanner.Plan plan = planner.plan(10_001, 0, 500, 20);

        assertThat(plan.totalResults()).isEqualTo(10_001L);
        assertThat(plan.totalPages()).isEqualTo(500);
        assertThat(plan.hasNext()).isFalse();
        assertThat(plan.slots()).hasSize(20);
    }
}
