package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.dto.CalendarEventDTO;
import com.watchwise.watchwise_api.calendar.dto.CalendarEventType;
import com.watchwise.watchwise_api.calendar.dto.CalendarSource;
import com.watchwise.watchwise_api.calendar.dto.EpisodeCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.MovieCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.SeasonCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.dto.SeriesCalendarContentDTO;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.WatchedCalendarKey;
import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventAssemblerTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate RELEASE_DATE = LocalDate.of(2026, 9, 12);

    private final CalendarEventAssembler assembler = new CalendarEventAssembler();

    @Test
    void shouldReturnOnlyEventsFromRequestedMonthAfterEvaluatingCompleteSchedules() {
        CalendarScheduleSnapshot septemberEpisode = episode("1396", 1, 1, RELEASE_DATE);
        CalendarScheduleSnapshot octoberEpisode = episode("1396", 1, 2, LocalDate.of(2026, 10, 12));

        List<CalendarEventDTO> events = assemble(List.of(septemberEpisode, octoberEpisode));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(CalendarEventType.EPISODE);
            assertThat(event.date()).isEqualTo(RELEASE_DATE);
        });
    }

    @Test
    void shouldExcludeNullDatesAndSeasonZero() {
        List<CalendarScheduleSnapshot> snapshots = List.of(
                episode("1396", 0, 1, RELEASE_DATE),
                episode("1396", 1, 1, null),
                episode("1396", 1, 2, RELEASE_DATE));

        List<CalendarEventDTO> events = assemble(snapshots);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo(CalendarEventType.EPISODE);
        assertThat(((EpisodeCalendarContentDTO) events.get(0).content()).episodeNumber()).isEqualTo(2);
    }

    @Test
    void shouldComputeReleaseStatusFromSuppliedClock() {
        List<CalendarEventDTO> events = assemble(List.of(
                movie("550", LocalDate.of(2026, 9, 14)),
                movie("603", LocalDate.of(2026, 9, 16))));

        assertThat(events).extracting(CalendarEventDTO::releaseStatus)
                .containsExactly(ReleaseStatus.RELEASED, ReleaseStatus.UPCOMING);
    }

    @Test
    void shouldTreatAReleaseOnTheCurrentDateAsReleased() {
        List<CalendarEventDTO> events = assemble(List.of(movie("550", LocalDate.of(2026, 9, 15))));

        assertThat(events).singleElement().extracting(CalendarEventDTO::releaseStatus)
                .isEqualTo(ReleaseStatus.RELEASED);
    }

    @Test
    void shouldUseTheDomainCivilDateInsteadOfTheUtcDate() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
        try {
            Clock utcClock = Clock.fixed(Instant.parse("2026-09-16T00:30:00Z"), ZoneOffset.UTC);
            CalendarAssemblyInput input = new CalendarAssemblyInput(
                    MONTH,
                    utcClock,
                    List.of(movie("550", LocalDate.of(2026, 9, 16))),
                    Map.of(),
                    Set.of(),
                    "BR",
                    "pt-BR",
                    completeness(Set.of(), Set.of()));

            List<CalendarEventDTO> events = assembler.assemble(input);

            assertThat(events).singleElement().extracting(CalendarEventDTO::releaseStatus)
                    .isEqualTo(ReleaseStatus.UPCOMING);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void shouldComputeWatchedAndUnwatchedMovieAndEpisodeEvents() {
        CalendarScheduleSnapshot watchedMovie = movie("550", RELEASE_DATE);
        CalendarScheduleSnapshot watchedEpisode = episode("1396", 1, 1, RELEASE_DATE);
        CalendarScheduleSnapshot unwatchedEpisode = episode("1396", 1, 3, RELEASE_DATE);

        List<CalendarEventDTO> events = assemble(
                List.of(watchedMovie, watchedEpisode, unwatchedEpisode),
                Map.of(
                        movieKey("550"), Set.of(CalendarSource.WATCHLIST),
                        seriesKey("1396"), Set.of(CalendarSource.IN_PROGRESS)),
                Set.of(WatchedCalendarKey.movie("550"), WatchedCalendarKey.episode("1396", 1, 1)));

        assertThat(events).extracting(CalendarEventDTO::watchStatus)
                .containsExactly(WatchStatus.WATCHED, WatchStatus.WATCHED, WatchStatus.UNWATCHED);
        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsExactly(
                        CalendarEventType.MOVIE, CalendarEventType.EPISODE, CalendarEventType.EPISODE);
    }

    @Test
    void shouldKeepWatchedEventsInOutput() {
        CalendarScheduleSnapshot movie = movie("550", RELEASE_DATE);

        List<CalendarEventDTO> events = assemble(
                List.of(movie),
                Map.of(movieKey("550"), Set.of(CalendarSource.WATCHLIST)),
                Set.of(WatchedCalendarKey.movie("550")));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.watchStatus()).isEqualTo(WatchStatus.WATCHED);
            assertThat(event.content()).isEqualTo(new MovieCalendarContentDTO("550", "Movie 550", "/movie-550.jpg"));
        });
    }

    @Test
    void shouldMergeSourcesForTheSameExternalIdentity() {
        CalendarScheduleKey movieInEnglish = new CalendarScheduleKey(ContentType.MOVIE, "550", "en-US", "US");
        CalendarScheduleKey movieInPortuguese = new CalendarScheduleKey(ContentType.MOVIE, "550", "pt-BR", "BR");
        CalendarScheduleKey seriesInEnglish = new CalendarScheduleKey(ContentType.SERIES, "1396", "en-US", "US");
        CalendarScheduleKey seriesInPortuguese = new CalendarScheduleKey(ContentType.SERIES, "1396", "pt-BR", "BR");

        List<CalendarEventDTO> events = assemble(
                List.of(
                        movie("550", RELEASE_DATE),
                        episode("1396", 1, 1, RELEASE_DATE),
                        episode("1396", 1, 3, RELEASE_DATE)),
                Map.of(
                        movieInEnglish, Set.of(CalendarSource.WATCHLIST),
                        movieInPortuguese, Set.of(CalendarSource.IN_PROGRESS),
                        seriesInEnglish, Set.of(CalendarSource.WATCHLIST),
                        seriesInPortuguese, Set.of(CalendarSource.IN_PROGRESS)),
                Set.of());

        assertThat(events.get(0).sources())
                .containsExactly(CalendarSource.IN_PROGRESS);
        assertThat(events.get(1).sources())
                .containsExactly(CalendarSource.IN_PROGRESS);
    }

    @Test
    void shouldGroupCompleteSeasonIntoOneEvent() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 2, 2, RELEASE_DATE),
                episode("1396", 2, 1, RELEASE_DATE)),
                Map.of(), Set.of(), completeness(Set.of(season("1396", 2)), Set.of()));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(CalendarEventType.SEASON);
            assertThat(event.watchStatus()).isEqualTo(WatchStatus.UNWATCHED);
            assertThat(event.content()).isEqualTo(
                    new SeasonCalendarContentDTO("1396", 2, 2, "Series 1396", "/series-1396.jpg"));
        });
    }

    @Test
    void shouldGroupCompleteSeriesAcrossRegularSeasonsIntoOneEvent() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 2, 1, RELEASE_DATE),
                episode("1396", 1, 2, RELEASE_DATE),
                episode("1396", 1, 1, RELEASE_DATE)),
                Map.of(), Set.of(), completeness(Set.of(), Set.of(seriesKey("1396"))));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(CalendarEventType.SERIES);
            assertThat(event.content()).isEqualTo(
                    new SeriesCalendarContentDTO("1396", 3, "Series 1396", "/series-1396.jpg"));
        });
    }

    @Test
    void shouldMarkGroupedEventPartiallyWatched() {
        List<CalendarEventDTO> events = assemble(
                List.of(
                        episode("1396", 1, 1, RELEASE_DATE),
                        episode("1396", 1, 2, RELEASE_DATE)),
                Map.of(seriesKey("1396"), Set.of(CalendarSource.IN_PROGRESS)),
                Set.of(WatchedCalendarKey.episode("1396", 1, 1)),
                completeness(Set.of(), Set.of(seriesKey("1396"))));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(CalendarEventType.SERIES);
            assertThat(event.watchStatus()).isEqualTo(WatchStatus.PARTIALLY_WATCHED);
        });
    }

    @Test
    void shouldMarkGroupedEventWatchedWhenEveryEpisodeIsWatched() {
        List<CalendarEventDTO> events = assemble(
                List.of(
                        episode("1396", 1, 1, RELEASE_DATE),
                        episode("1396", 1, 2, RELEASE_DATE)),
                Map.of(seriesKey("1396"), Set.of(CalendarSource.WATCHLIST)),
                Set.of(
                        WatchedCalendarKey.episode("1396", 1, 1),
                        WatchedCalendarKey.episode("1396", 1, 2)),
                completeness(Set.of(), Set.of(seriesKey("1396"))));

        assertThat(events).singleElement().extracting(CalendarEventDTO::watchStatus)
                .isEqualTo(WatchStatus.WATCHED);
    }

    @Test
    void shouldKeepIncompleteGroupsAsIndividualEpisodes() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 1, 1, RELEASE_DATE),
                episode("1396", 1, 3, RELEASE_DATE)));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsOnly(CalendarEventType.EPISODE);
        assertThat(events).extracting(event -> ((EpisodeCalendarContentDTO) event.content()).episodeNumber())
                .containsExactly(1, 3);
    }

    @Test
    void shouldNotGroupEpisodesWithDifferentDates() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 1, 2, LocalDate.of(2026, 9, 13)),
                episode("1396", 1, 1, RELEASE_DATE)));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsOnly(CalendarEventType.EPISODE);
    }

    @Test
    void shouldNotGroupEpisodesFromDifferentSeries() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 1, 1, RELEASE_DATE),
                episode("9999", 1, 1, RELEASE_DATE)),
                Map.of(), Set.of(), completeness(Set.of(), Set.of(seriesKey("1396"), seriesKey("9999"))));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsExactly(CalendarEventType.SERIES, CalendarEventType.SERIES);
        assertThat(events).extracting(event -> ((SeriesCalendarContentDTO) event.content()).seriesTmdbId())
                .containsExactly("1396", "9999");
    }

    @Test
    void shouldOrderEventsDeterministicallyByDateTypeAndCoordinates() {
        LocalDate date = RELEASE_DATE;
        List<CalendarEventDTO> events = assemble(List.of(
                episode("500", 1, 3, date),
                episode("300", 2, 1, LocalDate.of(2026, 9, 14)),
                episode("300", 1, 2, date),
                movie("200", date),
                episode("300", 1, 1, date),
                episode("300", 2, 2, LocalDate.of(2026, 9, 14)),
                episode("400", 1, 2, date),
                movie("100", date),
                episode("500", 1, 1, date)),
                Map.of(), Set.of(), completeness(
                        Set.of(season("300", 1), season("300", 2)),
                        Set.of(seriesKey("400"))));

        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsExactly(
                        CalendarEventType.MOVIE,
                        CalendarEventType.MOVIE,
                        CalendarEventType.SERIES,
                        CalendarEventType.SEASON,
                        CalendarEventType.EPISODE,
                        CalendarEventType.EPISODE,
                        CalendarEventType.SEASON);
        assertThat(events).extracting(CalendarEventDTO::date)
                .containsExactly(date, date, date, date, date, date, LocalDate.of(2026, 9, 14));
        assertThat(((MovieCalendarContentDTO) events.get(0).content()).tmdbId()).isEqualTo("100");
        assertThat(((MovieCalendarContentDTO) events.get(1).content()).tmdbId()).isEqualTo("200");
        assertThat(((SeriesCalendarContentDTO) events.get(2).content()).seriesTmdbId()).isEqualTo("400");
        assertThat(((SeasonCalendarContentDTO) events.get(3).content()).seriesTmdbId()).isEqualTo("300");
        assertThat(((EpisodeCalendarContentDTO) events.get(4).content()).episodeNumber()).isEqualTo(1);
        assertThat(((EpisodeCalendarContentDTO) events.get(5).content()).episodeNumber()).isEqualTo(3);
        assertThat(((SeasonCalendarContentDTO) events.get(6).content()).seasonNumber()).isEqualTo(2);
    }

    @Test
    void shouldDefensivelyCopyAssemblyInputCollections() {
        List<CalendarScheduleSnapshot> snapshots = new ArrayList<>(List.of(movie("550", RELEASE_DATE)));
        Map<CalendarScheduleKey, Set<CalendarSource>> sources = new LinkedHashMap<>();
        Set<CalendarSource> movieSources = new LinkedHashSet<>();
        movieSources.add(CalendarSource.WATCHLIST);
        sources.put(movieKey("550"), movieSources);
        Set<WatchedCalendarKey> watchedKeys = new LinkedHashSet<>();

        CalendarAssemblyInput input = new CalendarAssemblyInput(
                MONTH, CLOCK, snapshots, sources, watchedKeys, "BR", "pt-BR", completeness(Set.of(), Set.of()));
        snapshots.clear();
        movieSources.clear();
        sources.clear();
        watchedKeys.add(WatchedCalendarKey.movie("550"));

        List<CalendarEventDTO> events = assembler.assemble(input);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.watchStatus()).isEqualTo(WatchStatus.UNWATCHED);
            assertThat(event.sources()).containsExactly(CalendarSource.WATCHLIST);
        });
    }

    @Test
    void shouldKeepMonthOnlyPrefixAsIndividualEpisodesWithoutDeclaredCompleteness() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 1, 1, RELEASE_DATE),
                episode("1396", 1, 2, RELEASE_DATE)));

        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsExactly(CalendarEventType.EPISODE, CalendarEventType.EPISODE);
    }

    @Test
    void shouldKeepMissingEpisodeCoordinateAsIndividualEpisodesWithoutDeclaredCompleteness() {
        List<CalendarEventDTO> events = assemble(List.of(
                episode("1396", 1, 1, RELEASE_DATE),
                episode("1396", 1, 3, RELEASE_DATE)));

        assertThat(events).extracting(CalendarEventDTO::eventType)
                .containsExactly(CalendarEventType.EPISODE, CalendarEventType.EPISODE);
    }

    @Test
    void shouldNotMixSnapshotsOrSourcesFromDifferentLocales() {
        CalendarScheduleSnapshot brazilianMovie = movie("550", RELEASE_DATE);
        CalendarScheduleSnapshot americanMovie = movie("550", RELEASE_DATE).toBuilder()
                .region("US")
                .language("en-US")
                .build();

        List<CalendarEventDTO> events = assemble(
                List.of(brazilianMovie, americanMovie),
                Map.of(
                        movieKey("550"), Set.of(CalendarSource.IN_PROGRESS),
                        new CalendarScheduleKey(ContentType.MOVIE, "550", "en-US", "US"), Set.of(CalendarSource.WATCHLIST)),
                Set.of());

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.content()).isEqualTo(new MovieCalendarContentDTO("550", "Movie 550", "/movie-550.jpg"));
            assertThat(event.sources()).containsExactly(CalendarSource.IN_PROGRESS);
        });
    }

    @Test
    void shouldExcludeRowsAbsentFromTheLastTmdbSnapshot() {
        CalendarScheduleSnapshot absentMovie = movie("550", RELEASE_DATE).toBuilder()
                .presentInLastTmdbSnapshot(false)
                .build();

        assertThat(assemble(List.of(absentMovie))).isEmpty();
    }

    private List<CalendarEventDTO> assemble(List<CalendarScheduleSnapshot> snapshots) {
        return assemble(snapshots, Map.of(), Set.of());
    }

    private List<CalendarEventDTO> assemble(
            List<CalendarScheduleSnapshot> snapshots,
            Map<CalendarScheduleKey, Set<CalendarSource>> sources,
            Set<WatchedCalendarKey> watchedKeys) {
        return assemble(snapshots, sources, watchedKeys, completeness(Set.of(), Set.of()));
    }

    private List<CalendarEventDTO> assemble(
            List<CalendarScheduleSnapshot> snapshots,
            Map<CalendarScheduleKey, Set<CalendarSource>> sources,
            Set<WatchedCalendarKey> watchedKeys,
            CalendarAssemblyInput.Completeness completeness) {
        return assembler.assemble(new CalendarAssemblyInput(
                MONTH, CLOCK, snapshots, sources, watchedKeys, "BR", "pt-BR", completeness));
    }

    private CalendarAssemblyInput.Completeness completeness(
            Set<CalendarAssemblyInput.CompleteSeasonKey> completeSeasonKeys,
            Set<CalendarScheduleKey> completeSeriesKeys) {
        return new CalendarAssemblyInput.Completeness(completeSeasonKeys, completeSeriesKeys);
    }

    private CalendarAssemblyInput.CompleteSeasonKey season(String seriesTmdbId, int seasonNumber) {
        return new CalendarAssemblyInput.CompleteSeasonKey(seriesTmdbId, seasonNumber);
    }

    private CalendarScheduleKey movieKey(String tmdbId) {
        return new CalendarScheduleKey(ContentType.MOVIE, tmdbId, "pt-BR", "BR");
    }

    private CalendarScheduleKey seriesKey(String tmdbId) {
        return new CalendarScheduleKey(ContentType.SERIES, tmdbId, "pt-BR", "BR");
    }

    private CalendarScheduleSnapshot movie(String tmdbId, LocalDate releaseDate) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                .tmdbId(tmdbId)
                .region("BR")
                .language("pt-BR")
                .releaseDate(releaseDate)
                .title("Movie " + tmdbId)
                .posterPath("/movie-" + tmdbId + ".jpg")
                .lastCheckedAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .nextCheckAt(LocalDateTime.of(2026, 9, 2, 0, 0))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private CalendarScheduleSnapshot episode(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, LocalDate releaseDate) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .region("BR")
                .language("pt-BR")
                .releaseDate(releaseDate)
                .title("Episode " + episodeNumber)
                .seriesTitle("Series " + seriesTmdbId)
                .posterPath("/series-" + seriesTmdbId + ".jpg")
                .stillPath("/episode-" + episodeNumber + ".jpg")
                .lastCheckedAt(LocalDateTime.of(2026, 9, 1, 0, 0))
                .nextCheckAt(LocalDateTime.of(2026, 9, 2, 0, 0))
                .presentInLastTmdbSnapshot(true)
                .build();
    }
}
