package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeriesRuntimeCalculatorTest {

    private final SeriesRuntimeCalculator calculator = new SeriesRuntimeCalculator();

    @Test
    @DisplayName("Should Sum Known Runtimes And Exclude Specials")
    void shouldSumKnownRuntimesAndExcludeSpecials() {
        List<TmdbSeasonFullDetails> seasons = List.of(
                season(1, episode(50), episode(45)),
                season(2, episode(52)),
                season(0, episode(90)));
        List<TmdbSeasonSummary> summaries = List.of(
                summary(1, 2),
                summary(2, 1),
                summary(0, 1));

        SeriesRuntimeAggregate result = calculator.calculate(seasons, summaries);

        assertThat(result.totalRuntimeMinutes()).isEqualTo(147);
        assertThat(result.knownEpisodeCount()).isEqualTo(3);
        assertThat(result.averageRuntimeMinutes()).isEqualTo(49);
        assertThat(result.reportedEpisodeCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should Ignore Episodes With Null Runtime")
    void shouldIgnoreEpisodesWithNullRuntime() {
        SeriesRuntimeAggregate result = calculator.calculate(
                List.of(season(1, episode(null), episode(42))),
                List.of(summary(1, 2)));

        assertThat(result.totalRuntimeMinutes()).isEqualTo(42);
        assertThat(result.averageRuntimeMinutes()).isEqualTo(42);
        assertThat(result.knownEpisodeCount()).isEqualTo(1);
        assertThat(result.reportedEpisodeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should Return Null Runtime Values When No Episode Runtime Is Known")
    void shouldReturnNullRuntimeValuesWhenNoEpisodeRuntimeIsKnown() {
        SeriesRuntimeAggregate result = calculator.calculate(
                List.of(season(1, episode(null))),
                List.of(summary(1, 1)));

        assertThat(result.totalRuntimeMinutes()).isNull();
        assertThat(result.averageRuntimeMinutes()).isNull();
        assertThat(result.knownEpisodeCount()).isNull();
        assertThat(result.reportedEpisodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should Ignore Null Seasons And Seasons Without A Number")
    void shouldIgnoreNullSeasonsAndSeasonsWithoutANumber() {
        TmdbSeasonFullDetails seasonWithoutNumber = season(null, episode(30));

        SeriesRuntimeAggregate result = calculator.calculate(
                Arrays.asList(null, seasonWithoutNumber, season(1, episode(40))),
                Arrays.asList(null, summary(null, 10), summary(1, 1)));

        assertThat(result.totalRuntimeMinutes()).isEqualTo(40);
        assertThat(result.averageRuntimeMinutes()).isEqualTo(40);
        assertThat(result.knownEpisodeCount()).isEqualTo(1);
        assertThat(result.reportedEpisodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should Return Null Reported Episode Count When A Regular Summary Omits Episode Count")
    void shouldReturnNullReportedEpisodeCountWhenARegularSummaryOmitsEpisodeCount() {
        SeriesRuntimeAggregate result = calculator.calculate(
                List.of(season(1, episode(40)), season(2, episode(50))),
                List.of(summary(1, 1), summary(2, null)));

        assertThat(result.totalRuntimeMinutes()).isEqualTo(90);
        assertThat(result.averageRuntimeMinutes()).isEqualTo(45);
        assertThat(result.knownEpisodeCount()).isEqualTo(2);
        assertThat(result.reportedEpisodeCount()).isNull();
    }

    @Test
    @DisplayName("Should Round Average Runtime To The Nearest Minute")
    void shouldRoundAverageRuntimeToTheNearestMinute() {
        SeriesRuntimeAggregate result = calculator.calculate(
                List.of(season(1, episode(1), episode(2))),
                List.of(summary(1, 2)));

        assertThat(result.totalRuntimeMinutes()).isEqualTo(3);
        assertThat(result.averageRuntimeMinutes()).isEqualTo(2);
        assertThat(result.knownEpisodeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should Return Null Reported Episode Count When Summaries Are Missing")
    void shouldReturnNullReportedEpisodeCountWhenSummariesAreMissing() {
        SeriesRuntimeAggregate result = calculator.calculate(
                List.of(season(1, episode(40))), null);

        assertThat(result.totalRuntimeMinutes()).isEqualTo(40);
        assertThat(result.averageRuntimeMinutes()).isEqualTo(40);
        assertThat(result.knownEpisodeCount()).isEqualTo(1);
        assertThat(result.reportedEpisodeCount()).isNull();
    }

    private static TmdbSeasonFullDetails season(Integer seasonNumber, TmdbEpisodeSummary... episodes) {
        return new TmdbSeasonFullDetails(
                null, null, null, null, null, seasonNumber, List.of(episodes), null, null);
    }

    private static TmdbEpisodeSummary episode(Integer runtime) {
        return new TmdbEpisodeSummary(null, null, null, null, runtime, null, null);
    }

    private static TmdbSeasonSummary summary(Integer seasonNumber, Integer episodeCount) {
        return new TmdbSeasonSummary(seasonNumber, null, null, null, episodeCount, null);
    }
}
