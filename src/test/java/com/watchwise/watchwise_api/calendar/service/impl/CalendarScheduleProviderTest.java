package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarScheduleProviderTest {

    @Mock
    private TmdbClient tmdbClient;

    private CalendarScheduleProviderImpl provider;

    @BeforeEach
    void setUp() {
        provider = new CalendarScheduleProviderImpl(tmdbClient, Runnable::run);
    }

    @Test
    @DisplayName("[loadMovie] Should Prefer Regional Theatrical Date - When Multiple Release Types Exist")
    void shouldPreferRegionalTheatricalDateWhenMultipleReleaseTypesExist() {
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(found(new TmdbMovieReleaseDates(
                "550", List.of(new TmdbRegionReleaseDates("BR", List.of(
                release("2026-10-03T00:00:00.000Z", 1),
                release("2026-10-08T00:00:00.000Z", 3),
                release("2026-10-02T00:00:00.000Z", 4)))))));
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(found(movie("550", "Fight Club")));

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class, found -> {
            assertThat(found.batch().movie().releaseDate()).isEqualTo(LocalDate.of(2026, 10, 8));
            assertThat(found.batch().movie().title()).isEqualTo("Fight Club");
            assertThat(found.batch().origin()).isEqualTo(TmdbLookupOrigin.REMOTE);
        });
    }

    @Test
    @DisplayName("[loadMovie] Should Keep A Checked Movie Without Event Date - When The Region Is Absent")
    void shouldKeepACheckedMovieWithoutEventDateWhenTheRegionIsAbsent() {
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(found(new TmdbMovieReleaseDates(
                "550", List.of(new TmdbRegionReleaseDates("US", List.of(release("2026-10-08", 3)))))));
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(found(movie("550", "Fight Club")));

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class,
                found -> assertThat(found.batch().movie().releaseDate()).isNull());
    }

    @Test
    @DisplayName("[loadMovie] Should Keep A Checked Movie Without Event Date - When The Preferred Region Has No Date")
    void shouldKeepACheckedMovieWithoutEventDateWhenThePreferredRegionHasNoDate() {
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(found(new TmdbMovieReleaseDates(
                "550", List.of(new TmdbRegionReleaseDates("BR", List.of(release(null, 3)))))));
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(found(movie("550", "Fight Club")));

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class,
                found -> assertThat(found.batch().movie().releaseDate()).isNull());
    }

    @Test
    @DisplayName("[loadSeason] Should Not Call TMDB - When The Season Is Zero")
    void shouldNotCallTmdbWhenTheSeasonIsZero() {
        CalendarScheduleLookup result = provider.loadSeason("1396", 0, "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.NotFound.class);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[loadSeason] Should Map Every Episode Including Missing Air Dates - When A Season Is Found")
    void shouldMapEveryEpisodeIncludingMissingAirDatesWhenASeasonIsFound() {
        when(tmdbClient.getCalendarSeasonDetails("1396", 2, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                3572, "Season 2", null, "/season.jpg", null, 2, List.of(
                episode(1, "Seven Thirty-Seven", "2026-10-01", "/one.jpg"),
                episode(2, "Grilled", null, "/two.jpg")), null, null)));
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad")));

        CalendarScheduleLookup result = provider.loadSeason("1396", 2, "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class, found -> {
            assertThat(found.batch().season().seriesTitle()).isEqualTo("Breaking Bad");
            assertThat(found.batch().season().episodes()).extracting("episodeNumber", "title", "releaseDate", "stillPath")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, "Seven Thirty-Seven", LocalDate.of(2026, 10, 1), "/one.jpg"),
                            org.assertj.core.groups.Tuple.tuple(2, "Grilled", null, "/two.jpg"));
            assertThat(found.batch().season().episodeCoordinates()).containsExactly(1, 2);
        });
        verify(tmdbClient).getCalendarSeasonDetails("1396", 2, "pt-BR");
    }

    @Test
    @DisplayName("[loadMovie] Should Propagate Not Found - When TMDB Does Not Find The Release Data")
    void shouldPropagateNotFoundWhenTmdbDoesNotFindTheReleaseData() {
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(new TmdbLookupResult.NotFound<>());

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.NotFound.class);
        verify(tmdbClient, never()).getMovieFullDetails("550", "pt-BR");
    }

    @Test
    @DisplayName("[loadSeason] Should Propagate Unavailable - When TMDB Cannot Load The Season")
    void shouldPropagateUnavailableWhenTmdbCannotLoadTheSeason() {
        when(tmdbClient.getCalendarSeasonDetails("1396", 2, "pt-BR")).thenReturn(new TmdbLookupResult.Unavailable<>());

        CalendarScheduleLookup result = provider.loadSeason("1396", 2, "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.Unavailable.class);
        verify(tmdbClient, never()).getTvFullDetails("1396", "pt-BR");
    }

    @Test
    @DisplayName("[loadSeries] Should Load Every Regular Season And Exclude Specials")
    void shouldLoadEveryRegularSeasonAndExcludeSpecials() {
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(seriesWithSeasons("1396", List.of(
                new TmdbSeasonSummary(0, "Specials", null, null, 2, null),
                new TmdbSeasonSummary(2, "Season 2", null, null, 1, null),
                new TmdbSeasonSummary(1, "Season 1", null, null, 1, null)))));
        when(tmdbClient.getCalendarSeasonDetails("1396", 1, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                1, "Season 1", null, null, null, 1, List.of(episode(1, "Pilot", "2026-09-01", null)), null, null)));
        when(tmdbClient.getCalendarSeasonDetails("1396", 2, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                2, "Season 2", null, null, null, 2, List.of(episode(1, "Return", "2026-09-08", null)), null, null)));

        CalendarScheduleLookup result = provider.loadSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.FoundSeries.class, found -> {
            assertThat(found.schedule().seasons()).extracting(season -> season.schedule().seasonNumber())
                    .containsExactly(1, 2);
            assertThat(found.schedule().totalRegularEpisodeCount()).isEqualTo(2);
        });
        verify(tmdbClient, never()).getCalendarSeasonDetails("1396", 0, "pt-BR");
    }

    private static <T> TmdbLookupResult.Found<T> found(T value) {
        return new TmdbLookupResult.Found<>(value, TmdbLookupOrigin.REMOTE);
    }

    private static TmdbMovieReleaseDate release(String releaseDate, int type) {
        return new TmdbMovieReleaseDate(null, null, releaseDate, null, type);
    }

    private static TmdbMovieFullDetails movie(String id, String title) {
        return new TmdbMovieFullDetails(id, title, null, null, "/fight-club.jpg", null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static TmdbTvFullDetails series(String id, String name) {
        return new TmdbTvFullDetails(id, name, null, null, "/breaking-bad.jpg", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static TmdbTvFullDetails seriesWithSeasons(String id, List<TmdbSeasonSummary> seasons) {
        return new TmdbTvFullDetails(id, "Breaking Bad", null, null, "/breaking-bad.jpg", null, null,
                null, null, null, null, seasons, null, null, null, null, null, null, null, null, null);
    }

    private static TmdbEpisodeSummary episode(Integer number, String name, String airDate, String stillPath) {
        return new TmdbEpisodeSummary(number, name, null, airDate, null, stillPath, null);
    }
}
