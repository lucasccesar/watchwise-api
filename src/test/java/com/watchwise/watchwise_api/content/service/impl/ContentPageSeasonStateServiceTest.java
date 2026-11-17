package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPageSeasonStateServiceTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final String SERIES_TMDB_ID = "1399";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @Mock
    private DiaryEntryRepository diaryEntryRepository;
    @Mock
    private CalendarScheduleCompletenessRepository completenessRepository;
    @Mock
    private CalendarScheduleSnapshotRepository snapshotRepository;
    @Mock
    private CalendarScheduleRefreshService scheduleRefreshService;

    private ContentPageSeasonStateService service;

    @BeforeEach
    void setUp() {
        service = new ContentPageSeasonStateService(
                diaryEntryRepository,
                completenessRepository,
                snapshotRepository,
                scheduleRefreshService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldAdmitAtMostFourSeasonRepairsForOnePage() {
        List<ContentCoordinate> coordinates = IntStream.rangeClosed(1, 6)
                .mapToObj(seasonNumber -> new ContentCoordinate(
                        ContentType.SEASON, null, SERIES_TMDB_ID, seasonNumber, null))
                .toList();
        List<SeasonSummaryDTO> summaries = IntStream.rangeClosed(1, 6)
                .mapToObj(seasonNumber -> new SeasonSummaryDTO(
                        seasonNumber, "Season " + seasonNumber, null, null, 1, 1))
                .toList();
        Map<ContentCoordinate, ContentViewerStateDTO> existingStates = new LinkedHashMap<>();
        coordinates.forEach(coordinate -> existingStates.put(coordinate, viewerState()));
        List<DiaryEntryRepository.WatchedEpisodeCoordinateProjection> watchedEpisodes = IntStream.rangeClosed(1, 6)
                .mapToObj(seasonNumber -> new DiaryEntryRepository.WatchedEpisodeCoordinateRow(
                        SERIES_TMDB_ID, seasonNumber, 1))
                .map(row -> (DiaryEntryRepository.WatchedEpisodeCoordinateProjection) row)
                .toList();
        List<Integer> seasonNumbers = IntStream.rangeClosed(1, 6).boxed().toList();

        when(diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID, List.of(SERIES_TMDB_ID), List.of()))
                .thenReturn(watchedEpisodes);
        when(diaryEntryRepository.findSeasonViewerHistoryDates(VIEWER_ID, SERIES_TMDB_ID)).thenReturn(List.of());
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleCompleteness.GroupType.SEASON,
                SERIES_TMDB_ID,
                seasonNumbers,
                "US",
                "en-US"))
                .thenReturn(List.of());
        when(snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE,
                SERIES_TMDB_ID,
                seasonNumbers,
                "US",
                "en-US"))
                .thenReturn(List.of());
        when(scheduleRefreshService.refreshSeasonInBackground(
                eq(SERIES_TMDB_ID), anyInt(), eq("US"), eq("en-US")))
                .thenReturn(true);

        var result = service.resolve(
                VIEWER_ID, SERIES_TMDB_ID, "US", "en-US", coordinates, summaries, existingStates);

        ArgumentCaptor<Integer> admittedSeasons = ArgumentCaptor.forClass(Integer.class);
        verify(scheduleRefreshService, times(4)).refreshSeasonInBackground(
                eq(SERIES_TMDB_ID), admittedSeasons.capture(), eq("US"), eq("en-US"));
        assertThat(admittedSeasons.getAllValues()).containsExactly(1, 2, 3, 4);
        assertThat(result.viewerStates().values()).extracting(ContentViewerStateDTO::watchStatus)
                .containsOnly(WatchStatus.UNKNOWN);
    }

    @Test
    void trustedReleaseScheduleOverridesCompletedPassWhenAnotherEpisodeHasSinceReleased() {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null);
        CalendarScheduleCompleteness marker = CalendarScheduleCompleteness.builder()
                .groupType(CalendarScheduleCompleteness.GroupType.SEASON)
                .seriesTmdbId(SERIES_TMDB_ID)
                .seasonNumber(1)
                .region("US")
                .language("en-US")
                .expectedEpisodeCount(3)
                .complete(true)
                .lastCheckedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .scopeVersion(4L)
                .build();
        List<CalendarScheduleSnapshot> snapshots = List.of(
                snapshot(1, LocalDate.of(2026, 11, 10)),
                snapshot(2, LocalDate.of(2026, 11, 13)),
                snapshot(3, LocalDate.of(2026, 11, 15)));
        when(diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID, List.of(SERIES_TMDB_ID), List.of()))
                .thenReturn(List.of(new DiaryEntryRepository.WatchedEpisodeCoordinateRow(SERIES_TMDB_ID, 1, 1)));
        when(diaryEntryRepository.findSeasonViewerHistoryDates(VIEWER_ID, SERIES_TMDB_ID)).thenReturn(List.of());
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleCompleteness.GroupType.SEASON,
                SERIES_TMDB_ID,
                List.of(1),
                "US",
                "en-US"))
                .thenReturn(List.of(marker));
        when(snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE,
                SERIES_TMDB_ID,
                List.of(1),
                "US",
                "en-US"))
                .thenReturn(snapshots);

        ContentViewerStateDTO completedProjection = new ContentViewerStateDTO(
                WatchStatus.WATCHED, null, null, null, 1, null, 1, UUID.randomUUID(),
                false, null, false, null, List.of(), null, null);
        var result = service.resolve(VIEWER_ID, SERIES_TMDB_ID, "US", "en-US", List.of(coordinate),
                List.of(new SeasonSummaryDTO(1, "Season 1", null, null, 3, 2)),
                Map.of(coordinate, completedProjection));

        ContentViewerStateDTO state = result.viewerStates().get(coordinate);
        assertThat(state.watchStatus()).isEqualTo(WatchStatus.PARTIALLY_WATCHED);
        assertThat(state.watchedEpisodeCount()).isOne();
        assertThat(state.releasedEpisodeCount()).isEqualTo(2);
        assertThat(result.airedEpisodeCounts()).containsEntry(coordinate, 2);
    }

    @Test
    void trustedScheduleProvidesReleasedCountForUnwatchedSeasonWithAiredSummary() {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.SEASON, null, SERIES_TMDB_ID, 1, null);
        CalendarScheduleCompleteness marker = CalendarScheduleCompleteness.builder()
                .groupType(CalendarScheduleCompleteness.GroupType.SEASON)
                .seriesTmdbId(SERIES_TMDB_ID)
                .seasonNumber(1)
                .region("US")
                .language("en-US")
                .expectedEpisodeCount(2)
                .complete(true)
                .lastCheckedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .scopeVersion(4L)
                .build();
        List<CalendarScheduleSnapshot> snapshots = List.of(
                snapshot(1, LocalDate.of(2026, 11, 10)),
                snapshot(2, LocalDate.of(2026, 11, 13)));
        when(diaryEntryRepository.findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                VIEWER_ID, List.of(SERIES_TMDB_ID), List.of()))
                .thenReturn(List.of());
        when(diaryEntryRepository.findSeasonViewerHistoryDates(VIEWER_ID, SERIES_TMDB_ID)).thenReturn(List.of());
        when(completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleCompleteness.GroupType.SEASON,
                SERIES_TMDB_ID,
                List.of(1),
                "US",
                "en-US"))
                .thenReturn(List.of(marker));
        when(snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE, SERIES_TMDB_ID, List.of(1), "US", "en-US"))
                .thenReturn(snapshots);

        var result = service.resolve(
                VIEWER_ID, SERIES_TMDB_ID, "US", "en-US", List.of(coordinate),
                List.of(new SeasonSummaryDTO(1, "Season 1", null, null, 2, 2)),
                Map.of(coordinate, viewerState()));

        ContentViewerStateDTO state = result.viewerStates().get(coordinate);
        assertThat(state.watchStatus()).isEqualTo(WatchStatus.UNWATCHED);
        assertThat(state.watchedEpisodeCount()).isZero();
        assertThat(state.releasedEpisodeCount()).isEqualTo(2);
        assertThat(result.airedEpisodeCounts()).containsEntry(coordinate, 2);
    }

    private CalendarScheduleSnapshot snapshot(int episodeNumber, LocalDate releaseDate) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(SERIES_TMDB_ID)
                .seasonNumber(1)
                .episodeNumber(episodeNumber)
                .region("US")
                .language("en-US")
                .releaseDate(releaseDate)
                .title("Episode " + episodeNumber)
                .lastCheckedAt(LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC))
                .nextCheckAt(LocalDateTime.ofInstant(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private ContentViewerStateDTO viewerState() {
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED, null, null, null, null, null, 0, null,
                false, null, false, null, List.of(), null, null);
    }
}
