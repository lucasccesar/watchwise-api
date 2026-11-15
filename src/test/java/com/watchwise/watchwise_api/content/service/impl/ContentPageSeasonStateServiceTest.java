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

    private ContentViewerStateDTO viewerState() {
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED, null, null, null, null, null, 0, null,
                false, null, false, null, List.of(), null, null);
    }
}
