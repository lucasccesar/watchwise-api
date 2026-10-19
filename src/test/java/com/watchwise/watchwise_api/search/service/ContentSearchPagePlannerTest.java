package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSearchPagePlannerTest {

    private final ContentSearchPagePlanner planner = new ContentSearchPagePlanner();

    @Test
    @DisplayName("[plan] Should Interleave Movie And Series Sources - When Both Have Results")
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
    @DisplayName("[plan] Should Continue With The Movie Source - When The Series Source Is Exhausted")
    void shouldContinueWithMovieSourceWhenSeriesSourceIsExhausted() {
        assertThat(planner.plan(5, 2, 2, 4).slots()).containsExactly(
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 2),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 3),
                new ContentSearchPagePlanner.Slot(MovieOrSeriesType.MOVIE, 4));
    }

    @Test
    @DisplayName("[plan] Should Retain Empty Combined Metadata - When Both Sources Are Empty")
    void shouldRetainEmptyCombinedMetadataWhenBothSourcesAreEmpty() {
        assertThat(planner.plan(0, 0, 1, 18))
                .extracting(ContentSearchPagePlanner.Plan::totalResults,
                        ContentSearchPagePlanner.Plan::totalPages,
                        ContentSearchPagePlanner.Plan::hasNext)
                .containsExactly(0L, 0, false);
    }

    @Test
    @DisplayName("[plan] Should Continue With The Series Source - When The Movie Source Is Exhausted")
    void shouldContinueWithSeriesSourceWhenMovieSourceIsExhausted() {
        assertThat(planner.plan(2, 5, 2, 4).slots())
                .containsExactly(
                        new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 2),
                        new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 3),
                        new ContentSearchPagePlanner.Slot(MovieOrSeriesType.SERIES, 4));
    }

    @Test
    @DisplayName("[plan] Should Cap Reported Pages - When The Visual Page Reaches Maximum")
    void shouldCapReportedPagesWhenVisualPageReachesMaximum() {
        ContentSearchPagePlanner.Plan plan = planner.plan(10_001, 0, 500, 20);

        assertThat(plan.totalResults()).isEqualTo(10_001L);
        assertThat(plan.totalPages()).isEqualTo(500);
        assertThat(plan.hasNext()).isFalse();
        assertThat(plan.slots()).hasSize(20);
    }
}
