package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarScheduleProviderTest {

    @Mock
    private ContentScheduleReader scheduleReader;

    private CalendarScheduleProviderImpl provider;

    @BeforeEach
    void setUp() {
        provider = new CalendarScheduleProviderImpl(scheduleReader);
    }

    @Test
    @DisplayName("[loadMovie] Should Map The Shared Schedule - When The Movie Is Found")
    void shouldMapTheSharedScheduleWhenTheMovieIsFound() {
        when(scheduleReader.readMovie("550", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(movieSchedule(
                        LocalDate.of(2026, 10, 8), false), TmdbLookupOrigin.REMOTE));

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class, found -> {
            assertThat(found.batch().key()).isEqualTo(new CalendarScheduleKey(ContentType.MOVIE, "550", "pt-BR", "BR"));
            assertThat(found.batch().movie()).satisfies(movie -> {
                assertThat(movie.releaseDate()).isEqualTo(LocalDate.of(2026, 10, 8));
                assertThat(movie.title()).isEqualTo("Fight Club");
                assertThat(movie.posterPath()).isEqualTo("/fight-club.jpg");
            });
            assertThat(found.batch().origin()).isEqualTo(TmdbLookupOrigin.REMOTE);
        });
        verify(scheduleReader).readMovie("550", "BR", "pt-BR");
    }

    @Test
    @DisplayName("[loadMovie] Should Keep A Found Movie Without Event Date - When The Region Has No Date")
    void shouldKeepAFoundMovieWithoutEventDateWhenTheRegionHasNoDate() {
        when(scheduleReader.readMovie("550", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(movieSchedule(null, false), TmdbLookupOrigin.REMOTE));

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class,
                found -> assertThat(found.batch().movie().releaseDate()).isNull());
    }

    @Test
    @DisplayName("[loadMovie] Should Return Unavailable - When Release Dates Cannot Be Loaded")
    void shouldReturnUnavailableWhenReleaseDatesCannotBeLoaded() {
        when(scheduleReader.readMovie("550", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(movieSchedule(null, true), TmdbLookupOrigin.REMOTE));

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.Unavailable.class);
    }

    @Test
    @DisplayName("[loadMovie] Should Propagate Not Found - When The Shared Reader Does Not Find The Movie")
    void shouldPropagateNotFoundWhenTheSharedReaderDoesNotFindTheMovie() {
        when(scheduleReader.readMovie("550", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.NotFound());

        CalendarScheduleLookup result = provider.loadMovie("550", "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.NotFound.class);
    }

    @Test
    @DisplayName("[loadSeason] Should Map Every Shared Episode - When The Season Is Found")
    void shouldMapEverySharedEpisodeWhenTheSeasonIsFound() {
        ContentSchedule schedule = new ContentSchedule(
                ContentScheduleKey.season("1396", 2),
                LocalDate.of(2026, 9, 1),
                "Returning Series",
                List.of(
                        new ContentScheduleEpisode("1396", 2, 1, LocalDate.of(2026, 10, 1), "Seven Thirty-Seven", "/one.jpg"),
                        new ContentScheduleEpisode("1396", 2, 2, null, "Grilled", "/two.jpg")),
                true,
                false,
                "Breaking Bad",
                "/breaking-bad.jpg",
                Map.of());
        when(scheduleReader.readSeason("1396", 2, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(schedule, TmdbLookupOrigin.CACHE));

        CalendarScheduleLookup result = provider.loadSeason("1396", 2, "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.Found.class, found -> {
            assertThat(found.batch().origin()).isEqualTo(TmdbLookupOrigin.CACHE);
            assertThat(found.batch().season().seriesTitle()).isEqualTo("Breaking Bad");
            assertThat(found.batch().season().episodes()).extracting("episodeNumber", "title", "releaseDate", "stillPath")
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, "Seven Thirty-Seven", LocalDate.of(2026, 10, 1), "/one.jpg"),
                            org.assertj.core.groups.Tuple.tuple(2, "Grilled", null, "/two.jpg"));
        });
        verify(scheduleReader).readSeason("1396", 2, "BR", "pt-BR");
    }

    @Test
    @DisplayName("[loadSeason] Should Return Not Found Without Calling The Reader - When The Season Is Invalid")
    void shouldReturnNotFoundWithoutCallingTheReaderWhenTheSeasonIsInvalid() {
        CalendarScheduleLookup result = provider.loadSeason("1396", 0, "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.NotFound.class);
        verifyNoInteractions(scheduleReader);
    }

    @Test
    @DisplayName("[loadSeason] Should Propagate Unavailable - When The Shared Reader Cannot Load The Season")
    void shouldPropagateUnavailableWhenTheSharedReaderCannotLoadTheSeason() {
        when(scheduleReader.readSeason("1396", 2, "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Unavailable());

        CalendarScheduleLookup result = provider.loadSeason("1396", 2, "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.Unavailable.class);
    }

    @Test
    @DisplayName("[loadSeries] Should Map The Shared Episodes Into Regular Seasons")
    void shouldMapTheSharedEpisodesIntoRegularSeasons() {
        ContentSchedule schedule = new ContentSchedule(
                ContentScheduleKey.series("1396"),
                LocalDate.of(2008, 1, 20),
                "Ended",
                List.of(
                        new ContentScheduleEpisode("1396", 1, 1, LocalDate.of(2026, 9, 1), "Pilot", null),
                        new ContentScheduleEpisode("1396", 2, 1, LocalDate.of(2026, 9, 8), "Return", null)),
                true,
                false,
                "Breaking Bad",
                "/breaking-bad.jpg",
                Map.of(1, 1, 2, 1));
        when(scheduleReader.readSeries("1396", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(schedule, TmdbLookupOrigin.REMOTE,
                        Map.of(1, TmdbLookupOrigin.REMOTE, 2, TmdbLookupOrigin.CACHE)));

        CalendarScheduleLookup result = provider.loadSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.FoundSeries.class, found -> {
            assertThat(found.schedule().seasons()).extracting(season -> season.schedule().seasonNumber())
                    .containsExactly(1, 2);
            assertThat(found.schedule().seasons()).extracting(CalendarSeriesSchedule.Season::origin)
                    .containsExactly(TmdbLookupOrigin.REMOTE, TmdbLookupOrigin.CACHE);
            assertThat(found.schedule().totalRegularEpisodeCount()).isEqualTo(2);
            assertThat(found.schedule().completeSchedule()).isTrue();
        });
    }

    @Test
    @DisplayName("[loadSeries] Should Return Not Found With The Missing Season - When The Shared Reader Reports It")
    void shouldReturnNotFoundWithTheMissingSeasonWhenTheSharedReaderReportsIt() {
        when(scheduleReader.readSeries("1396", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.NotFound(2));

        CalendarScheduleLookup result = provider.loadSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOfSatisfying(CalendarScheduleLookup.NotFound.class,
                notFound -> assertThat(notFound.seasonNumber()).isEqualTo(2));
    }

    @Test
    @DisplayName("[loadSeries] Should Return Unavailable - When The Shared Schedule Is Incomplete")
    void shouldReturnUnavailableWhenTheSharedScheduleIsIncomplete() {
        ContentSchedule schedule = new ContentSchedule(
                ContentScheduleKey.series("1396"),
                null,
                "Ended",
                List.of(new ContentScheduleEpisode("1396", 1, 1, LocalDate.of(2026, 9, 1), "Pilot", null)),
                false,
                false,
                "Breaking Bad",
                "/breaking-bad.jpg",
                Map.of(1, 2));
        when(scheduleReader.readSeries("1396", "BR", "pt-BR"))
                .thenReturn(new ContentScheduleLookup.Found(schedule, TmdbLookupOrigin.REMOTE,
                        Map.of(1, TmdbLookupOrigin.REMOTE)));

        CalendarScheduleLookup result = provider.loadSeries("1396", "BR", "pt-BR");

        assertThat(result).isInstanceOf(CalendarScheduleLookup.Unavailable.class);
    }

    private static ContentSchedule movieSchedule(LocalDate releaseDate, boolean releaseDateLookupUnavailable) {
        return new ContentSchedule(
                ContentScheduleKey.movie("550"),
                releaseDate,
                "Released",
                List.of(),
                true,
                releaseDateLookupUnavailable,
                "Fight Club",
                "/fight-club.jpg",
                Map.of());
    }
}
