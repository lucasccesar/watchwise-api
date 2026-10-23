package com.watchwise.watchwise_api.diarycompletion.service;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeWatch;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.impl.CalendarCompletionScopeLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarCompletionScopeLoaderTest {

    private static final String SERIES_ID = "123";
    private static final String REGION = "BR";
    private static final String LANGUAGE = "pt-BR";
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-22");

    @Mock
    private CalendarScheduleProvider scheduleProvider;

    private CalendarCompletionScopeLoader loader;

    @BeforeEach
    void setUp() {
        loader = new CalendarCompletionScopeLoader(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Ignore An Unstarted Future Season - When Earlier Seasons Are Complete")
    void shouldIgnoreAnUnstartedFutureSeasonWhenEarlierSeasonsAreComplete() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 2,
                        episode(3, LocalDate.parse("2026-10-22")),
                        episode(1, LocalDate.parse("2026-10-01"))),
                season(2, 2,
                        episode(1, LocalDate.parse("2026-11-01")),
                        episode(2, LocalDate.parse("2026-11-08"))));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().episodes()).containsExactlyInAnyOrder(
                new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 3));
        assertThat(result.scope().releaseComplete()).isTrue();
        assertThat(result.validUntil()).isEqualTo(NOW.plusSeconds(7L * 24 * 60 * 60));
        assertThat(result.scheduleVersion()).isNotBlank();
        verify(scheduleProvider).loadSeries(SERIES_ID, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Keep A Series Incomplete - When No Regular Season Has Started")
    void shouldKeepASeriesIncompleteWhenNoRegularSeasonHasStarted() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 2,
                        episode(1, LocalDate.parse("2026-11-01")),
                        episode(3, LocalDate.parse("2026-11-08"))));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().episodes()).isEmpty();
        assertThat(result.scope().releaseComplete()).isFalse();
    }

    @Test
    @DisplayName("[load] Should Preserve Actual Coordinates - When Released Seasons Have Different Episode Numbers")
    void shouldPreserveActualCoordinatesWhenReleasedSeasonsHaveDifferentEpisodeNumbers() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 2,
                        episode(1, LocalDate.parse("2026-10-01")),
                        episode(3, LocalDate.parse("2026-10-08"))),
                season(2, 2,
                        episode(1, LocalDate.parse("2026-10-10")),
                        episode(2, LocalDate.parse("2026-10-15"))));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().episodes()).containsExactlyInAnyOrder(
                new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 3),
                new EpisodeCoordinate(2, 1), new EpisodeCoordinate(2, 2));
        assertThat(result.scope().releaseComplete()).isTrue();
    }

    @Test
    @DisplayName("[load] Should Use The Target Count Without Loading Sibling Seasons")
    void shouldUseTheTargetCountWithoutLoadingSiblingSeasons() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(3, LocalDate.parse("2026-10-22")),
                episode(1, LocalDate.parse("2026-10-01")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));
        VersionedCompletionScope[] result = new VersionedCompletionScope[1];

        assertThatCode(() -> result[0] = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW))
                .doesNotThrowAnyException();

        assertThat(result[0].scope().episodes()).containsExactlyInAnyOrder(
                new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 3));
        assertThat(result[0].scope().releaseComplete()).isTrue();
        verify(scheduleProvider).loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Produce Stable Finite Target Metadata - When Season Data Is Unchanged")
    void shouldProduceStableFiniteTargetMetadataWhenSeasonDataIsUnchanged() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(3, LocalDate.parse("2026-10-24")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope[] results = new VersionedCompletionScope[2];
        assertThatCode(() -> {
            results[0] = loader.load(MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);
            results[1] = loader.load(MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);
        }).doesNotThrowAnyException();

        assertThat(results[0].scheduleVersion()).isEqualTo(results[1].scheduleVersion());
        assertThat(results[0].validUntil()).isEqualTo(results[1].validUntil()).isAfter(NOW);
        verify(scheduleProvider, org.mockito.Mockito.times(2))
                .loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Version The Expected Count - When Target Season Data Changes")
    void shouldVersionTheExpectedCountWhenTargetSeasonDataChanges() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(3, LocalDate.parse("2026-10-08")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2))
                .thenReturn(foundSeason(directSeason, 3));

        VersionedCompletionScope first = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);
        VersionedCompletionScope second = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);

        assertThat(first.scheduleVersion()).isNotEqualTo(second.scheduleVersion());
        assertThat(first.scope().releaseComplete()).isTrue();
        assertThat(second.scope().releaseComplete()).isFalse();
    }

    @Test
    @DisplayName("[load] Should Use A Finite Target Recheck - When No Future Release Is Announced")
    void shouldUseAFiniteTargetRecheckWhenNoFutureReleaseIsAnnounced() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(3, LocalDate.parse("2026-10-08")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope result = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);

        assertThat(result.validUntil()).isEqualTo(
                CalendarScheduleCadence.nextSeriesCheckAt(LocalDate.parse("2026-10-08"), NOW));
        assertThat(result.validUntil()).isAfter(NOW);
        assertThat(result.scope().releaseComplete()).isTrue();
        verify(scheduleProvider).loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Align Target Eligibility And Validity - At Local Release Midnight")
    void shouldAlignTargetEligibilityAndValidityAtLocalReleaseMidnight() {
        LocalDate releaseDate = LocalDate.parse("2026-10-22");
        Instant localReleaseStart = releaseDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")), episode(3, releaseDate));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope beforeRelease = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, localReleaseStart.minusSeconds(1));
        VersionedCompletionScope atRelease = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, localReleaseStart);

        assertThat(beforeRelease.scope().releaseComplete()).isFalse();
        assertThat(beforeRelease.validUntil()).isEqualTo(localReleaseStart);
        assertThat(atRelease.scope().releaseComplete()).isTrue();
    }

    @Test
    @DisplayName("[load] Should Keep A Season Incomplete - When Its Expected Count Is Missing")
    void shouldKeepASeasonIncompleteWhenItsExpectedCountIsMissing() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(3, LocalDate.parse("2026-10-22")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason));

        VersionedCompletionScope[] result = new VersionedCompletionScope[1];
        assertThatCode(() -> result[0] = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW))
                .doesNotThrowAnyException();

        assertThat(result[0].scope().releaseComplete()).isFalse();
        verify(scheduleProvider).loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Keep A Season Incomplete - When A Required Release Date Is Unknown")
    void shouldKeepASeasonIncompleteWhenARequiredReleaseDateIsUnknown() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(3, null));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope result = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(result.scope().episodes()).containsExactlyInAnyOrder(
                new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 3));
        assertThat(result.validUntil()).isAfter(NOW);
        verify(scheduleProvider).loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Keep A Season Incomplete - When Episode Coordinates Are Not Unique")
    void shouldKeepASeasonIncompleteWhenEpisodeCoordinatesAreNotUnique() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(1, LocalDate.parse("2026-10-08")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope result = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(result.scope().episodes()).containsExactly(new EpisodeCoordinate(1, 1));
    }

    @Test
    @DisplayName("[load] Should Keep A Season Incomplete - When An Episode Coordinate Is Not Positive")
    void shouldKeepASeasonIncompleteWhenAnEpisodeCoordinateIsNotPositive() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(0, LocalDate.parse("2026-10-01")),
                episode(1, LocalDate.parse("2026-10-08")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope result = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(result.scope().episodes()).containsExactly(new EpisodeCoordinate(1, 1));
    }

    @Test
    @DisplayName("[load] Should Distinguish Season Not Found - When The Target Season Does Not Exist")
    void shouldDistinguishSeasonNotFoundWhenTheTargetSeasonDoesNotExist() {
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.NotFound(1));

        assertThatThrownBy(() -> loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW))
                .isInstanceOf(NotFoundException.class);

        verify(scheduleProvider).loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Distinguish Season Unavailable - When The Target Calendar Cannot Be Loaded")
    void shouldDistinguishSeasonUnavailableWhenTheTargetCalendarCannotBeLoaded() {
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.Unavailable());

        assertThatThrownBy(() -> loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW))
                .isInstanceOf(TmdbUnavailableException.class);

        verify(scheduleProvider).loadSeason(SERIES_ID, 1, REGION, LANGUAGE);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Exclude Special Season Zero - Even When A Schedule Is Returned")
    void shouldExcludeSpecialSeasonZeroEvenWhenAScheduleIsReturned() {
        assertThatThrownBy(() -> loader.load(
                MediaReference.season(SERIES_ID, 0), REGION, LANGUAGE, NOW))
                .isInstanceOf(BadRequestException.class);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Keep A Season Incomplete - When Its Known Finale Is In The Future")
    void shouldKeepASeasonIncompleteWhenItsKnownFinaleIsInTheFuture() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 2,
                        episode(1, LocalDate.parse("2026-10-01")),
                        episode(3, LocalDate.parse("2026-10-23"))));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(result.scope().episodes()).containsExactlyInAnyOrder(
                new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 3));
        assertThat(result.validUntil()).isEqualTo(
                LocalDate.parse("2026-10-23").atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("[load] Should Keep A Season Incomplete - When An Episode Release Date Is Unknown")
    void shouldKeepASeasonIncompleteWhenAnEpisodeReleaseDateIsUnknown() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 2,
                        episode(1, LocalDate.parse("2026-10-01")),
                        episode(3, null)));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(result.scope().episodes()).containsExactlyInAnyOrder(
                new EpisodeCoordinate(1, 1), new EpisodeCoordinate(1, 3));
    }

    @Test
    @DisplayName("[load] Should Reject An Episode Bonus - When Count Exceeds The Series Expectation")
    void shouldRejectAnEpisodeBonusWhenCountExceedsTheSeriesExpectation() {
        CalendarSeasonSchedule directSeason = seasonSchedule(1,
                episode(1, LocalDate.parse("2026-10-01")),
                episode(2, LocalDate.parse("2026-10-08")),
                episode(99, LocalDate.parse("2026-10-15")));
        when(scheduleProvider.loadSeason(SERIES_ID, 1, REGION, LANGUAGE))
                .thenReturn(foundSeason(directSeason, 2));

        VersionedCompletionScope[] result = new VersionedCompletionScope[1];
        assertThatCode(() -> result[0] = loader.load(
                MediaReference.season(SERIES_ID, 1), REGION, LANGUAGE, NOW))
                .doesNotThrowAnyException();

        assertThat(result[0].scope().releaseComplete()).isFalse();
        assertThat(result[0].scope().episodes()).contains(new EpisodeCoordinate(1, 99));
    }

    @Test
    @DisplayName("[load] Should Produce No Completion Coordinates - When Season Has Zero Episodes")
    void shouldProduceNoCompletionCoordinatesWhenSeasonHasZeroEpisodes() {
        CalendarSeriesSchedule schedule = seriesSchedule(true, season(1, 0));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().episodes()).isEmpty();
        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(result.validUntil()).isEqualTo(NOW.plusSeconds(7L * 24 * 60 * 60));
        assertThat(new CompletionCalculator().calculate(result.scope(), List.<EpisodeWatch>of())).isEmpty();
    }

    @Test
    @DisplayName("[load] Should Reject A Series Scope - When An Expected Season Is Not Represented")
    void shouldRejectASeriesScopeWhenAnExpectedSeasonIsNotRepresented() {
        CalendarSeriesSchedule schedule = new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, SERIES_ID, LANGUAGE, REGION),
                List.of(season(1, 2,
                        episode(1, LocalDate.parse("2026-10-01")),
                        episode(3, LocalDate.parse("2026-10-08")))),
                Map.of(1, 2, 2, 2),
                4,
                false);
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isFalse();
        assertThat(new CompletionCalculator().calculate(result.scope(), List.of(
                new EpisodeWatch(java.util.UUID.randomUUID(), new EpisodeCoordinate(1, 1), 1,
                        TODAY, NOW, null),
                new EpisodeWatch(java.util.UUID.randomUUID(), new EpisodeCoordinate(1, 3), 1,
                        TODAY, NOW, null)))).isEmpty();
    }

    @Test
    @DisplayName("[load] Should Trust Complete Expected Coverage - When Series Data Came From Cache")
    void shouldTrustCompleteExpectedCoverageWhenSeriesDataCameFromCache() {
        CalendarSeriesSchedule schedule = seriesSchedule(false,
                season(1, 1, episode(3, LocalDate.parse("2026-10-01"))));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().episodes()).containsExactly(new EpisodeCoordinate(1, 3));
        assertThat(result.scope().releaseComplete()).isTrue();
    }

    @Test
    @DisplayName("[load] Should Treat Today's Episodes As Released - At The Calendar Date Boundary")
    void shouldTreatTodaysEpisodesAsReleasedAtTheCalendarDateBoundary() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 2,
                        episode(1, LocalDate.parse("2026-10-21")),
                        episode(3, TODAY)));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.scope().releaseComplete()).isTrue();
    }

    @Test
    @DisplayName("[load] Should Use The Calendar Zone - Immediately Before And At Local Release Midnight")
    void shouldUseTheCalendarZoneImmediatelyBeforeAndAtLocalReleaseMidnight() {
        LocalDate releaseDate = LocalDate.parse("2026-10-22");
        ZoneId calendarZone = ZoneId.systemDefault();
        Instant localReleaseStart = releaseDate.atStartOfDay(calendarZone).toInstant();
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 1, episode(3, LocalDate.parse("2026-10-01"))),
                season(2, 1, episode(1, releaseDate)));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope beforeRelease = loader.load(
                MediaReference.series(SERIES_ID), REGION, LANGUAGE, localReleaseStart.minusSeconds(1));
        VersionedCompletionScope atRelease = loader.load(
                MediaReference.series(SERIES_ID), REGION, LANGUAGE, localReleaseStart);
        EpisodeCoordinate previousEpisode = new EpisodeCoordinate(1, 3);
        List<EpisodeWatch> watches = List.of(new EpisodeWatch(
                java.util.UUID.randomUUID(), previousEpisode, 1, LocalDate.parse("2026-10-01"), NOW, null));

        assertThat(beforeRelease.scope().releaseComplete()).isTrue();
        assertThat(beforeRelease.scope().episodes()).containsExactly(previousEpisode);
        assertThat(beforeRelease.validUntil()).isEqualTo(localReleaseStart);
        assertThat(atRelease.scope().releaseComplete()).isTrue();
        assertThat(atRelease.scope().episodes()).containsExactlyInAnyOrder(
                previousEpisode, new EpisodeCoordinate(2, 1));
        assertThat(new CompletionCalculator().calculate(beforeRelease.scope(), watches))
                .extracting("watchNumber").containsExactly(1);
        assertThat(new CompletionCalculator().calculate(atRelease.scope(), watches)).isEmpty();
    }

    @Test
    @DisplayName("[load] Should Distinguish Not Found - When The Series Does Not Exist")
    void shouldDistinguishNotFoundWhenTheSeriesDoesNotExist() {
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.NotFound());

        assertThatThrownBy(() -> loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("[load] Should Distinguish Unavailable - When The Series Calendar Cannot Be Loaded")
    void shouldDistinguishUnavailableWhenTheSeriesCalendarCannotBeLoaded() {
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.Unavailable());

        assertThatThrownBy(() -> loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    @DisplayName("[load] Should Exclude Special Season Zero - When It Is Requested Directly")
    void shouldExcludeSpecialSeasonZeroWhenItIsRequestedDirectly() {
        assertThatThrownBy(() -> loader.load(MediaReference.season(SERIES_ID, 0), REGION, LANGUAGE, NOW))
                .isInstanceOf(BadRequestException.class);
        verifyNoMoreInteractions(scheduleProvider);
    }

    @Test
    @DisplayName("[load] Should Produce A Stable Version - When Schedule Data Is Unchanged")
    void shouldProduceAStableVersionWhenScheduleDataIsUnchanged() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 1, episode(3, LocalDate.parse("2026-10-22"))));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope first = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);
        VersionedCompletionScope second = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW.plusSeconds(1));

        assertThat(first.scheduleVersion()).isEqualTo(second.scheduleVersion());
    }

    @Test
    @DisplayName("[load] Should Use Series Discovery Cadence - When No Future Release Is Announced")
    void shouldUseSeriesDiscoveryCadenceWhenNoFutureReleaseIsAnnounced() {
        CalendarSeriesSchedule schedule = seriesSchedule(true,
                season(1, 1, episode(3, TODAY)));
        when(scheduleProvider.loadSeries(SERIES_ID, REGION, LANGUAGE))
                .thenReturn(new CalendarScheduleLookup.FoundSeries(schedule));

        VersionedCompletionScope result = loader.load(MediaReference.series(SERIES_ID), REGION, LANGUAGE, NOW);

        assertThat(result.validUntil()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
    }

    private static CalendarScheduleLookup.Found foundSeason(CalendarSeasonSchedule schedule) {
        return foundSeason(schedule, null);
    }

    private static CalendarScheduleLookup.Found foundSeason(
            CalendarSeasonSchedule schedule,
            Integer expectedEpisodeCount) {
        return new CalendarScheduleLookup.Found(CalendarScheduleBatch.season(
                new CalendarScheduleKey(ContentType.SERIES, SERIES_ID, LANGUAGE, REGION),
                TmdbLookupOrigin.REMOTE,
                schedule,
                expectedEpisodeCount));
    }

    private static CalendarSeriesSchedule seriesSchedule(
            boolean completeSchedule,
            CalendarSeriesSchedule.Season... seasons) {
        Map<Integer, Integer> expectedCounts = new LinkedHashMap<>();
        for (CalendarSeriesSchedule.Season season : seasons) {
            expectedCounts.put(season.schedule().seasonNumber(), season.expectedEpisodeCount());
        }
        int total = expectedCounts.values().stream().mapToInt(Integer::intValue).sum();
        return new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, SERIES_ID, LANGUAGE, REGION),
                new ArrayList<>(List.of(seasons)),
                expectedCounts,
                total,
                completeSchedule);
    }

    private static CalendarSeriesSchedule.Season season(
            int seasonNumber,
            int expectedEpisodeCount,
            CalendarEpisodeSchedule... episodes) {
        return new CalendarSeriesSchedule.Season(
                seasonSchedule(seasonNumber, episodes), expectedEpisodeCount, TmdbLookupOrigin.REMOTE);
    }

    private static CalendarSeasonSchedule seasonSchedule(
            int seasonNumber,
            CalendarEpisodeSchedule... episodes) {
        return new CalendarSeasonSchedule(
                SERIES_ID, seasonNumber, REGION, LANGUAGE, "Series", null, List.of(episodes));
    }

    private static CalendarEpisodeSchedule episode(int number, LocalDate releaseDate) {
        return new CalendarEpisodeSchedule(number, "Episode " + number, releaseDate, null, null, null);
    }
}
