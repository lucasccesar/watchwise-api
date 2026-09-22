package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentProductionStatus;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.dto.ReleaseStatus;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContentStateResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T02:30:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final String SERIES_ID = "1396";

    private final ContentStateResolver resolver = new ContentStateResolver();

    @Test
    @DisplayName("Should Return Watched Movie - When Its Direct Content ID Was Watched")
    void shouldReturnWatchedMovieWhenItsDirectContentIdWasWatched() {
        Content movie = content(ContentType.MOVIE, "550", null, null, null);

        ContentStateDTO result = resolve(movie, movieSchedule(LocalDate.of(2020, 1, 1), "Released"),
                Set.of(movie.getId()), Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.WATCHED, ReleaseStatus.RELEASED, ContentProductionStatus.FINISHED, null, null));
    }

    @Test
    @DisplayName("Should Return Unwatched Movie - When Its Direct Content ID Was Not Watched")
    void shouldReturnUnwatchedMovieWhenItsDirectContentIdWasNotWatched() {
        Content movie = content(ContentType.MOVIE, "550", null, null, null);

        ContentStateDTO result = resolve(movie, movieSchedule(LocalDate.of(2026, 9, 15), "Planned"),
                Set.of(), Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNWATCHED, ReleaseStatus.UPCOMING, ContentProductionStatus.PRE_RELEASE, null, null));
    }

    @Test
    @DisplayName("Should Return Watched Episode - When Its Direct Content ID Was Watched")
    void shouldReturnWatchedEpisodeWhenItsDirectContentIdWasWatched() {
        Content episode = content(ContentType.EPISODE, null, SERIES_ID, 1, 1);

        ContentStateDTO result = resolve(episode, seriesSchedule(LocalDate.of(2008, 1, 20), "Returning Series", List.of()),
                Set.of(episode.getId()), Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.WATCHED, ReleaseStatus.RELEASED, null, null, null));
    }

    @Test
    @DisplayName("Should Return Unwatched Episode - When Its Direct Content ID Was Not Watched")
    void shouldReturnUnwatchedEpisodeWhenItsDirectContentIdWasNotWatched() {
        Content episode = content(ContentType.EPISODE, null, SERIES_ID, 1, 1);

        ContentStateDTO result = resolve(episode, seriesSchedule(null, null, List.of()), Set.of(), Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNWATCHED, ReleaseStatus.UNKNOWN, null, null, null));
    }

    @Test
    @DisplayName("Should Return Unwatched Season - When No Released Episodes Were Watched")
    void shouldReturnUnwatchedSeasonWhenNoReleasedEpisodesWereWatched() {
        Content season = content(ContentType.SEASON, null, SERIES_ID, 1, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(1)),
                episode(SERIES_ID, 1, 2, TODAY));

        ContentStateDTO result = resolve(season, seasonSchedule(1, episodes, true), Set.of(), Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNWATCHED, ReleaseStatus.UNKNOWN, ContentProductionStatus.FINISHED, 0, 2));
    }

    @Test
    @DisplayName("Should Return Unwatched Series - When No Released Episodes Were Watched")
    void shouldReturnUnwatchedSeriesWhenNoReleasedEpisodesWereWatched() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(1)),
                episode(SERIES_ID, 2, 1, TODAY));

        ContentStateDTO result = resolve(series, seriesSchedule(TODAY.minusDays(10), "Ended", episodes), Set.of(), Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNWATCHED, ReleaseStatus.RELEASED, ContentProductionStatus.FINISHED, 0, 2));
    }

    @Test
    @DisplayName("Should Return Partially Watched Season - When Some Released Episodes Were Watched")
    void shouldReturnPartiallyWatchedSeasonWhenSomeReleasedEpisodesWereWatched() {
        Content season = content(ContentType.SEASON, null, SERIES_ID, 1, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(2)),
                episode(SERIES_ID, 1, 2, TODAY.minusDays(1)),
                episode(SERIES_ID, 1, 3, TODAY));

        ContentStateDTO result = resolve(
                season,
                seasonSchedule(1, episodes, true),
                Set.of(),
                Set.of(coordinate(1, 2)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.PARTIALLY_WATCHED, ReleaseStatus.UNKNOWN, ContentProductionStatus.FINISHED, 1, 3));
    }

    @Test
    @DisplayName("Should Return Watched Series - When All Released Episodes Were Watched Even With Future Episodes")
    void shouldReturnWatchedSeriesWhenAllReleasedEpisodesWereWatchedEvenWithFutureEpisodes() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(2)),
                episode(SERIES_ID, 1, 2, TODAY),
                episode(SERIES_ID, 1, 3, TODAY.plusDays(7)));

        ContentStateDTO result = resolve(
                series,
                seriesSchedule(TODAY.minusDays(10), "Returning Series", episodes),
                Set.of(),
                Set.of(coordinate(1, 1), coordinate(1, 2)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.WATCHED, ReleaseStatus.RELEASED, ContentProductionStatus.IN_PROGRESS, 2, 2));
    }

    @Test
    @DisplayName("Should Include A Newly Released Episode - When The Previous Marker Covered Only Older Episodes")
    void shouldIncludeANewlyReleasedEpisodeWhenThePreviousMarkerCoveredOnlyOlderEpisodes() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(30)),
                episode(SERIES_ID, 1, 2, TODAY));

        ContentStateDTO result = resolve(
                series,
                seriesSchedule(TODAY.minusDays(30), "Returning Series", episodes),
                Set.of(),
                Set.of(coordinate(1, 1)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.PARTIALLY_WATCHED, ReleaseStatus.RELEASED, ContentProductionStatus.IN_PROGRESS, 1, 2));
    }

    @Test
    @DisplayName("Should Count Distinct Released Episode Coordinates - When A Rewatch Is Present")
    void shouldCountDistinctReleasedEpisodeCoordinatesWhenARewatchIsPresent() {
        Content season = content(ContentType.SEASON, null, SERIES_ID, 1, null);
        ContentScheduleEpisode episode = episode(SERIES_ID, 1, 1, TODAY.minusDays(1));

        ContentStateDTO result = resolve(
                season,
                seasonSchedule(1, List.of(episode, episode), true),
                Set.of(),
                Set.of(coordinate(1, 1)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.WATCHED, ReleaseStatus.UNKNOWN, ContentProductionStatus.FINISHED, 1, 1));
    }

    @Test
    @DisplayName("Should Return Unknown Aggregate Watch State - When The Schedule Is Incomplete")
    void shouldReturnUnknownAggregateWatchStateWhenTheScheduleIsIncomplete() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(1)));

        ContentStateDTO result = resolve(
                series,
                seriesSchedule(TODAY.minusDays(10), "Ended", episodes, false),
                Set.of(),
                Set.of(coordinate(1, 1)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNKNOWN, ReleaseStatus.RELEASED, ContentProductionStatus.FINISHED, null, null));
    }

    @Test
    @DisplayName("Should Return Unwatched Aggregate - When No Episodes Have Been Released Yet")
    void shouldReturnUnwatchedAggregateWhenNoEpisodesHaveBeenReleasedYet() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.plusDays(1)));

        ContentStateDTO result = resolve(
                series,
                seriesSchedule(TODAY.minusDays(10), "Returning Series", episodes),
                Set.of(),
                Set.of());

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNWATCHED, ReleaseStatus.RELEASED, ContentProductionStatus.IN_PROGRESS, 0, 0));
    }

    @Test
    @DisplayName("Should Exclude Episodes Without Dates - When Calculating Released Episode Counts")
    void shouldExcludeEpisodesWithoutDatesWhenCalculatingReleasedEpisodeCounts() {
        Content season = content(ContentType.SEASON, null, SERIES_ID, 1, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 1, 1, TODAY.minusDays(1)),
                episode(SERIES_ID, 1, 2, null),
                episode(SERIES_ID, 1, 3, TODAY.plusDays(1)));

        ContentStateDTO result = resolve(
                season,
                seasonSchedule(1, episodes, true),
                Set.of(),
                Set.of(coordinate(1, 2)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.UNWATCHED, ReleaseStatus.UNKNOWN, ContentProductionStatus.FINISHED, 0, 1));
    }

    @Test
    @DisplayName("Should Respect The Clock Zone - When Release Date Is Today In The Application Zone")
    void shouldRespectTheClockZoneWhenReleaseDateIsTodayInTheApplicationZone() {
        Content movie = content(ContentType.MOVIE, "550", null, null, null);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T02:30:00Z"), ZoneId.of("America/Sao_Paulo"));

        ContentStateDTO result = resolver.resolve(
                movie,
                movieSchedule(TODAY, "Released"),
                Set.of(),
                Set.of(),
                clock);

        assertThat(result.releaseStatus()).isEqualTo(ReleaseStatus.RELEASED);
    }

    @Test
    @DisplayName("Should Return Unknown Release Status - When The Release Date Is Absent")
    void shouldReturnUnknownReleaseStatusWhenTheReleaseDateIsAbsent() {
        Content movie = content(ContentType.MOVIE, "550", null, null, null);

        ContentStateDTO result = resolve(movie, movieSchedule(null, "Released"), Set.of(), Set.of());

        assertThat(result.releaseStatus()).isEqualTo(ReleaseStatus.UNKNOWN);
    }

    @ParameterizedTest(name = "{0} maps to {1}")
    @CsvSource({
            "Released, FINISHED",
            "Ended, FINISHED",
            "Canceled, CANCELLED",
            "Returning Series, IN_PROGRESS",
            "In Production, IN_PROGRESS",
            "Post Production, IN_PROGRESS",
            "Planned, PRE_RELEASE",
            "Pilot, PRE_RELEASE",
            "Rumored, PRE_RELEASE",
            "Unexpected, UNKNOWN"
    })
    @DisplayName("Should Normalize TMDB Production Status")
    void shouldNormalizeTmdbProductionStatus(String externalStatus, ContentProductionStatus expectedStatus) {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);

        ContentStateDTO result = resolve(
                series,
                seriesSchedule(TODAY.minusDays(1), externalStatus, List.of()),
                Set.of(),
                Set.of());

        assertThat(result.productionStatus()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("Should Return Null Production Status - For Episodes")
    void shouldReturnNullProductionStatusForEpisodes() {
        Content episode = content(ContentType.EPISODE, null, SERIES_ID, 1, 1);

        ContentStateDTO result = resolve(episode, seriesSchedule(TODAY, "Ended", List.of()), Set.of(), Set.of());

        assertThat(result.productionStatus()).isNull();
    }

    @Test
    @DisplayName("Should Ignore Season Zero - When Resolving A Series")
    void shouldIgnoreSeasonZeroWhenResolvingASeries() {
        Content series = content(ContentType.SERIES, SERIES_ID, null, null, null);
        List<ContentScheduleEpisode> episodes = List.of(
                episode(SERIES_ID, 0, 1, TODAY.minusDays(1)),
                episode(SERIES_ID, 1, 1, TODAY.minusDays(1)));

        ContentStateDTO result = resolve(
                series,
                seriesSchedule(TODAY.minusDays(10), "Ended", episodes),
                Set.of(),
                Set.of(coordinate(0, 1), coordinate(1, 1)));

        assertThat(result).isEqualTo(new ContentStateDTO(
                WatchStatus.WATCHED, ReleaseStatus.RELEASED, ContentProductionStatus.FINISHED, 1, 1));
    }

    private ContentStateDTO resolve(
            Content content,
            ContentSchedule schedule,
            Set<UUID> watchedDirectContentIds,
            Set<WatchedEpisodeCoordinate> watchedEpisodeCoordinates) {
        return resolver.resolve(content, schedule, watchedDirectContentIds, watchedEpisodeCoordinates, CLOCK);
    }

    private Content content(
            ContentType type,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber) {
        return Content.builder()
                .id(UUID.randomUUID())
                .type(type)
                .tmdbId(tmdbId)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .build();
    }

    private ContentSchedule movieSchedule(LocalDate releaseDate, String externalStatus) {
        return new ContentSchedule(ContentScheduleKey.movie("550"), releaseDate, externalStatus, List.of(), true, false);
    }

    private ContentSchedule seasonSchedule(Integer seasonNumber, List<ContentScheduleEpisode> episodes, boolean complete) {
        return new ContentSchedule(
                ContentScheduleKey.season(SERIES_ID, seasonNumber),
                null,
                "Ended",
                episodes,
                complete,
                false);
    }

    private ContentSchedule seriesSchedule(LocalDate releaseDate, String externalStatus, List<ContentScheduleEpisode> episodes) {
        return seriesSchedule(releaseDate, externalStatus, episodes, true);
    }

    private ContentSchedule seriesSchedule(
            LocalDate releaseDate,
            String externalStatus,
            List<ContentScheduleEpisode> episodes,
            boolean complete) {
        return new ContentSchedule(
                ContentScheduleKey.series(SERIES_ID),
                releaseDate,
                externalStatus,
                episodes,
                complete,
                false);
    }

    private ContentScheduleEpisode episode(
            String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, LocalDate releaseDate) {
        return new ContentScheduleEpisode(seriesTmdbId, seasonNumber, episodeNumber, releaseDate);
    }

    private WatchedEpisodeCoordinate coordinate(Integer seasonNumber, Integer episodeNumber) {
        return new WatchedEpisodeCoordinate(SERIES_ID, seasonNumber, episodeNumber);
    }
}
