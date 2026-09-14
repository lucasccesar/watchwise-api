package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
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
class ContentScheduleReaderImplTest {

    @Mock
    private com.watchwise.watchwise_api.common.tmdb.TmdbClient tmdbClient;

    private ContentScheduleReaderImpl reader;

    @BeforeEach
    void setUp() {
        reader = new ContentScheduleReaderImpl(tmdbClient, Runnable::run);
    }

    @Test
    @DisplayName("[readMovie] Should Read The Preferred Regional Release Date - When Details And Releases Exist")
    void shouldReadThePreferredRegionalReleaseDateWhenDetailsAndReleasesExist() {
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(found(movie("550", "Fight Club", "Released")));
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(found(new TmdbMovieReleaseDates(
                "550", List.of(new TmdbRegionReleaseDates("BR", List.of(
                release("2026-10-03T00:00:00.000Z", 1),
                release("2026-10-08T00:00:00.000Z", 3)))))));

        ContentScheduleLookup result = reader.readMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().key()).isEqualTo(new ContentScheduleKey(ContentType.MOVIE, "550", null, null));
            assertThat(found.schedule().releaseDate()).isEqualTo(LocalDate.of(2026, 10, 8));
            assertThat(found.schedule().externalStatus()).isEqualTo("Released");
            assertThat(found.schedule().episodes()).isEmpty();
            assertThat(found.schedule().complete()).isTrue();
            assertThat(found.schedule().releaseDateLookupUnavailable()).isFalse();
        });
    }

    @Test
    @DisplayName("[readMovie] Should Distinguish A Missing Regional Date - From An Unavailable Release-Date Lookup")
    void shouldDistinguishAMissingRegionalDateFromAnUnavailableReleaseDateLookup() {
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(found(movie("550", "Fight Club", "Released")));
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(found(new TmdbMovieReleaseDates(
                "550", List.of(new TmdbRegionReleaseDates("US", List.of(release("2026-10-08", 3)))))));

        ContentScheduleLookup regionalDateMissing = reader.readMovie("550", "BR", "pt-BR");

        assertThat(regionalDateMissing).isInstanceOfSatisfying(ContentScheduleLookup.Found.class,
                found -> assertThat(found.schedule().releaseDateLookupUnavailable()).isFalse());
        assertThat(((ContentScheduleLookup.Found) regionalDateMissing).schedule().releaseDate()).isNull();

        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(new TmdbLookupResult.Unavailable<>());

        ContentScheduleLookup releaseDatesUnavailable = reader.readMovie("550", "BR", "pt-BR");

        assertThat(releaseDatesUnavailable).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().releaseDate()).isNull();
            assertThat(found.schedule().externalStatus()).isEqualTo("Released");
            assertThat(found.schedule().releaseDateLookupUnavailable()).isTrue();
        });
    }

    @Test
    @DisplayName("[readMovie] Should Return Not Found - When Movie Details Do Not Exist")
    void shouldReturnNotFoundWhenMovieDetailsDoNotExist() {
        when(tmdbClient.getMovieFullDetails("999", "pt-BR")).thenReturn(new TmdbLookupResult.NotFound<>());

        ContentScheduleLookup result = reader.readMovie("999", "BR", "pt-BR");

        assertThat(result).isInstanceOf(ContentScheduleLookup.NotFound.class);
        verify(tmdbClient, never()).getMovieReleaseDates("999", "pt-BR");
    }

    @Test
    @DisplayName("[readMovie] Should Keep The Known Movie - When Release-Date Lookup Returns Not Found")
    void shouldKeepTheKnownMovieWhenReleaseDateLookupReturnsNotFound() {
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(found(movie("550", "Fight Club", "Released")));
        when(tmdbClient.getMovieReleaseDates("550", "pt-BR")).thenReturn(new TmdbLookupResult.NotFound<>());

        ContentScheduleLookup result = reader.readMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().externalStatus()).isEqualTo("Released");
            assertThat(found.schedule().releaseDate()).isNull();
            assertThat(found.schedule().releaseDateLookupUnavailable()).isTrue();
        });
    }

    @Test
    @DisplayName("[readMovie] Should Return Unavailable - When Movie Details Cannot Be Loaded")
    void shouldReturnUnavailableWhenMovieDetailsCannotBeLoaded() {
        when(tmdbClient.getMovieFullDetails("550", "pt-BR")).thenReturn(new TmdbLookupResult.Unavailable<>());

        ContentScheduleLookup result = reader.readMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOf(ContentScheduleLookup.Unavailable.class);
        verify(tmdbClient, never()).getMovieReleaseDates("550", "pt-BR");
    }

    @Test
    @DisplayName("[readSeason] Should Read The Series Status And Every Episode - When Season Exists")
    void shouldReadTheSeriesStatusAndEveryEpisodeWhenSeasonExists() {
        when(tmdbClient.getSeasonFullDetails("1396", 2, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                3572, "Season 2", null, "/season.jpg", "2026-09-01", 2, List.of(
                episode(1, "Seven Thirty-Seven", "2026-10-01"),
                episode(2, "Grilled", null)), null, null)));
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad", "Returning Series", null)));

        ContentScheduleLookup result = reader.readSeason("1396", 2, "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().key()).isEqualTo(new ContentScheduleKey(ContentType.SEASON, null, "1396", 2));
            assertThat(found.schedule().releaseDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(found.schedule().externalStatus()).isEqualTo("Returning Series");
            assertThat(found.schedule().episodes()).extracting(
                            ContentScheduleEpisode::seriesTmdbId,
                            ContentScheduleEpisode::seasonNumber,
                            ContentScheduleEpisode::episodeNumber,
                            ContentScheduleEpisode::releaseDate)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("1396", 2, 1, LocalDate.of(2026, 10, 1)),
                            org.assertj.core.groups.Tuple.tuple("1396", 2, 2, null));
            assertThat(found.schedule().complete()).isTrue();
        });
        verify(tmdbClient).getSeasonFullDetails("1396", 2, "pt-BR");
        verify(tmdbClient, never()).getCalendarSeasonDetails("1396", 2, "pt-BR");
    }

    @Test
    @DisplayName("[readSeason] Should Mark The Schedule Incomplete - When The Episode List Is Missing")
    void shouldMarkTheScheduleIncompleteWhenTheEpisodeListIsMissing() {
        when(tmdbClient.getSeasonFullDetails("1396", 2, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                3572, "Season 2", null, null, "2026-09-01", 2, null, null, null)));
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad", "Ended", null)));

        ContentScheduleLookup result = reader.readSeason("1396", 2, "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().episodes()).isEmpty();
            assertThat(found.schedule().complete()).isFalse();
        });
    }

    @Test
    @DisplayName("[readSeason] Should Return Not Found Without Calling TMDB - When The Season Number Is Invalid")
    void shouldReturnNotFoundWithoutCallingTmdbWhenTheSeasonNumberIsInvalid() {
        ContentScheduleLookup result = reader.readSeason("1396", 0, "BR", "pt-BR");

        assertThat(result).isInstanceOf(ContentScheduleLookup.NotFound.class);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[readSeries] Should Read Regular Seasons And Concatenate Episodes - While Excluding Specials")
    void shouldReadRegularSeasonsAndConcatenateEpisodesWhileExcludingSpecials() {
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad", "Ended", List.of(
                new TmdbSeasonSummary(0, "Specials", null, null, 2, null),
                new TmdbSeasonSummary(2, "Season 2", null, null, 1, null),
                new TmdbSeasonSummary(1, "Season 1", null, null, 1, null)))));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                1, "Season 1", null, null, "2026-09-01", 1, List.of(episode(1, "Pilot", "2026-09-01")), null, null)));
        when(tmdbClient.getSeasonFullDetails("1396", 2, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                2, "Season 2", null, null, "2026-09-08", 2, List.of(episode(1, "Return", "2026-09-08")), null, null)));

        ContentScheduleLookup result = reader.readSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().key()).isEqualTo(new ContentScheduleKey(ContentType.SERIES, "1396", null, null));
            assertThat(found.schedule().releaseDate()).isNull();
            assertThat(found.schedule().externalStatus()).isEqualTo("Ended");
            assertThat(found.schedule().episodes()).extracting(ContentScheduleEpisode::seasonNumber)
                    .containsExactly(1, 2);
            assertThat(found.schedule().complete()).isTrue();
        });
        verify(tmdbClient, never()).getSeasonFullDetails("1396", 0, "pt-BR");
    }

    @Test
    @DisplayName("[readSeries] Should Mark The Schedule Incomplete - When A Season Episode Count Diverges")
    void shouldMarkTheScheduleIncompleteWhenASeasonEpisodeCountDiverges() {
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad", "Ended", List.of(
                new TmdbSeasonSummary(1, "Season 1", null, null, 2, null)))));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                1, "Season 1", null, null, "2026-09-01", 1,
                List.of(episode(1, "Pilot", "2026-09-01")), null, null)));

        ContentScheduleLookup result = reader.readSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class, found -> {
            assertThat(found.schedule().episodes()).hasSize(1);
            assertThat(found.schedule().complete()).isFalse();
        });
    }

    @Test
    @DisplayName("[readSeries] Should Mark The Schedule Incomplete - When A Season Repeats An Episode Number")
    void shouldMarkTheScheduleIncompleteWhenASeasonRepeatsAnEpisodeNumber() {
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad", "Ended", List.of(
                new TmdbSeasonSummary(1, "Season 1", null, null, 2, null)))));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                1, "Season 1", null, null, "2026-09-01", 1,
                List.of(episode(1, "Pilot", "2026-09-01"), episode(1, "Pilot Again", "2026-09-08")), null, null)));

        ContentScheduleLookup result = reader.readSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class,
                found -> assertThat(found.schedule().complete()).isFalse());
    }

    @Test
    @DisplayName("[readSeries] Should Mark The Schedule Incomplete - When Regular Season Summaries Repeat A Season Number")
    void shouldMarkTheScheduleIncompleteWhenRegularSeasonSummariesRepeatASeasonNumber() {
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(found(series("1396", "Breaking Bad", "Ended", List.of(
                new TmdbSeasonSummary(1, "Season 1", null, null, 1, null),
                new TmdbSeasonSummary(1, "Season 1 (duplicate)", null, null, 2, null)))));
        when(tmdbClient.getSeasonFullDetails("1396", 1, "pt-BR")).thenReturn(found(new TmdbSeasonFullDetails(
                1, "Season 1", null, null, "2026-09-01", 1,
                List.of(episode(1, "Pilot", "2026-09-01")), null, null)));

        ContentScheduleLookup result = reader.readSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(ContentScheduleLookup.Found.class,
                found -> assertThat(found.schedule().complete()).isFalse());
    }

    @Test
    @DisplayName("[readSeries] Should Return Unavailable - When The Series Details Cannot Be Loaded")
    void shouldReturnUnavailableWhenTheSeriesDetailsCannotBeLoaded() {
        when(tmdbClient.getTvFullDetails("1396", "pt-BR")).thenReturn(new TmdbLookupResult.Unavailable<>());

        ContentScheduleLookup result = reader.readSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOf(ContentScheduleLookup.Unavailable.class);
    }

    private static <T> TmdbLookupResult.Found<T> found(T value) {
        return new TmdbLookupResult.Found<>(value, TmdbLookupOrigin.REMOTE);
    }

    private static TmdbMovieReleaseDate release(String releaseDate, int type) {
        return new TmdbMovieReleaseDate(null, null, releaseDate, null, type);
    }

    private static TmdbMovieFullDetails movie(String id, String title, String status) {
        return new TmdbMovieFullDetails(id, title, null, null, "/fight-club.jpg", null, null, null,
                null, null, null, null, null, null, null, null, null, status);
    }

    private static TmdbTvFullDetails series(
            String id, String name, String status, List<TmdbSeasonSummary> seasons) {
        return new TmdbTvFullDetails(id, name, null, null, "/breaking-bad.jpg", null, null, null,
                null, null, null, seasons, null, null, null, null, null, null, null, null, status);
    }

    private static TmdbEpisodeSummary episode(Integer number, String name, String airDate) {
        return new TmdbEpisodeSummary(number, name, null, airDate, null, null, null);
    }
}
