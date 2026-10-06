package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeriesProgressNextEpisodeResolverImplTest {

    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final SeriesProgressNextEpisodeResolverImpl resolver =
            new SeriesProgressNextEpisodeResolverImpl(tmdbClient);

    @Test
    void shouldReturnTheFirstUnwatchedEpisodeWhenAnEpisodeWasSkipped() {
        TmdbTvFullDetails series = seriesWithSeasons(
                new TmdbSeasonSummary(1, "Season 1", null, "2020-01-01", 3, null));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(season(1,
                        episode(1, "One", "2020-01-01"),
                        episode(2, "Two", "2020-01-08"),
                        episode(3, "Three", "2020-01-15"))));

        ProgressEpisodeDTO result = resolver.resolveNext(
                series,
                "en-US",
                List.of(new SeasonProgressDTO(1, 2L, 3, 66.6)),
                Set.of(
                        new WatchedEpisodeCoordinate("1399", 1, 1),
                        new WatchedEpisodeCoordinate("1399", 1, 3)),
                LocalDate.of(2026, 10, 6));

        assertThat(result.seasonNumber()).isEqualTo(1);
        assertThat(result.episodeNumber()).isEqualTo(2);
        assertThat(result.title()).isEqualTo("Two");
        assertThat(result.availableToWatch()).isTrue();
    }

    @Test
    void shouldReturnAFutureEpisodeAsUnavailableAfterSkippingCompletedSeasons() {
        TmdbTvFullDetails series = seriesWithSeasons(
                new TmdbSeasonSummary(0, "Specials", null, null, 1, null),
                new TmdbSeasonSummary(1, "Season 1", null, "2020-01-01", 1, null),
                new TmdbSeasonSummary(2, "Season 2", null, "2099-01-01", 1, null));
        when(tmdbClient.getSeasonFullDetails("1399", 2, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(season(2,
                        episode(1, "Future", "2099-01-08"))));

        ProgressEpisodeDTO result = resolver.resolveNext(
                series,
                "en-US",
                List.of(new SeasonProgressDTO(1, 1L, 1, 100.0)),
                Set.of(new WatchedEpisodeCoordinate("1399", 1, 1)),
                LocalDate.of(2026, 10, 6));

        assertThat(result.episodeNumber()).isEqualTo(1);
        assertThat(result.seasonNumber()).isEqualTo(2);
        assertThat(result.availableToWatch()).isFalse();
    }

    @Test
    void shouldReturnNullWhenEveryRegularEpisodeIsWatched() {
        TmdbTvFullDetails series = seriesWithSeasons(
                new TmdbSeasonSummary(1, "Season 1", null, "2020-01-01", 2, null));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(season(1,
                        episode(1, "One", "2020-01-01"),
                        episode(2, "Two", "2020-01-08"))));

        ProgressEpisodeDTO result = resolver.resolveNext(
                series,
                "en-US",
                List.of(new SeasonProgressDTO(1, 2L, 2, 100.0)),
                Set.of(
                        new WatchedEpisodeCoordinate("1399", 1, 1),
                        new WatchedEpisodeCoordinate("1399", 1, 2)),
                LocalDate.of(2026, 10, 6));

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullWhenTmdbDoesNotHaveTheCandidateSeason() {
        TmdbTvFullDetails series = seriesWithSeasons(
                new TmdbSeasonSummary(1, "Season 1", null, "2020-01-01", 1, null));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "en-US"))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        ProgressEpisodeDTO result = resolver.resolveNext(
                series, "en-US", List.of(), Set.of(), LocalDate.of(2026, 10, 6));

        assertThat(result).isNull();
    }

    @Test
    void shouldPropagateTmdbUnavailableForTheCandidateSeason() {
        TmdbTvFullDetails series = seriesWithSeasons(
                new TmdbSeasonSummary(1, "Season 1", null, "2020-01-01", 1, null));
        when(tmdbClient.getSeasonFullDetails("1399", 1, "en-US"))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> resolver.resolveNext(
                series, "en-US", List.of(), Set.of(), LocalDate.of(2026, 10, 6)))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    private TmdbTvFullDetails seriesWithSeasons(TmdbSeasonSummary... seasons) {
        TmdbTvFullDetails series = mock(TmdbTvFullDetails.class);
        when(series.id()).thenReturn("1399");
        when(series.seasons()).thenReturn(List.of(seasons));
        return series;
    }

    private TmdbSeasonFullDetails season(Integer seasonNumber, TmdbEpisodeSummary... episodes) {
        return new TmdbSeasonFullDetails(
                seasonNumber, null, null, null, null, seasonNumber, List.of(episodes), null, null);
    }

    private TmdbEpisodeSummary episode(Integer number, String name, String airDate) {
        return new TmdbEpisodeSummary(number, name, null, airDate, 50, "/still.jpg", null);
    }
}
