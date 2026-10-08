package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarMovieReleaseDateSelectorTest {

    @Test
    @DisplayName("[selectRelease] Should Clear The Time - When Same Priority And Date Have Distinct Timestamps")
    void shouldClearTheTimeWhenSamePriorityAndDateHaveDistinctTimestamps() {
        TmdbMovieReleaseDates releases = new TmdbMovieReleaseDates(
                "550",
                List.of(new TmdbRegionReleaseDates("BR", List.of(
                        release("2026-10-08T21:00:00.000Z"),
                        release("2026-10-08T22:00:00.000Z")))));

        CalendarMovieReleaseDateSelector.SelectedRelease selected =
                CalendarMovieReleaseDateSelector.selectRelease(releases, "BR").orElseThrow();

        assertThat(selected.date()).isEqualTo(java.time.LocalDate.of(2026, 10, 8));
        assertThat(selected.time()).isNull();
    }

    private static TmdbMovieReleaseDate release(String value) {
        return new TmdbMovieReleaseDate(null, null, value, null, 3);
    }
}
