package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpisodeRatingsReleasedEpisodesTest {

    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final EpisodeRatingsReleasedEpisodes reader = new EpisodeRatingsReleasedEpisodes(
            tmdbClient, new SeriesRuntimeCalculator(), executor);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldKeepReleasedEpisodeNumbersWhenSeasonHasGap() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        when(tmdbClient.getSeasonFullDetails("1399", 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Found<>(season(1, List.of(
                        episode(1, "2026-10-01"),
                        episode(3, "2026-10-06"),
                        episode(4, "2026-10-07"),
                        episode(5, null)))));

        assertThat(reader.load("1399", List.of(1), today)).isEqualTo(Map.of(1, List.of(1, 3)));
        verify(tmdbClient).getSeasonFullDetails("1399", 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
    }

    @Test
    void shouldFailWhenSeasonDetailsAreUnavailable() {
        when(tmdbClient.getSeasonFullDetails("1399", 1, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> reader.load("1399", List.of(1), LocalDate.of(2026, 10, 6)))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    private TmdbSeasonFullDetails season(int number, List<TmdbEpisodeSummary> episodes) {
        return new TmdbSeasonFullDetails(null, null, null, null, null, number, episodes, null, null);
    }

    private TmdbEpisodeSummary episode(int number, String airDate) {
        return new TmdbEpisodeSummary(number, null, null, airDate, null, null, null);
    }
}
