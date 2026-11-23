package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressAssembler;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressPresentationEnricher;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReadResult;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesProgressReaderImplTest {

    @Mock
    private SeriesProgressReadRepository seriesProgressReadRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private SeriesProgressMetadataRefreshService metadataRefreshService;

    @Mock
    private SeriesProgressPresentationEnricher presentationEnricher;

    @Mock
    private UserRepository userRepository;

    @Mock
    private User viewer;

    private SeriesProgressReaderImpl reader;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T03:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        reader = new SeriesProgressReaderImpl(
                seriesProgressReadRepository,
                diaryEntryRepository,
                metadataRefreshService,
                new SeriesProgressAssembler(),
                presentationEnricher,
                userRepository,
                clock);
    }

    @Test
    void loadsOneSnapshotBatchForDistinctSeriesIdsAndReturnsAllSeries() {
        List<String> seriesIds = List.of("1396", "94605");
        SeriesProgressReadRepository.SeriesProgressCandidate unwatched = row("1396", 0L);
        SeriesProgressReadRepository.SeriesProgressCandidate completed = row("94605", 2L);
        when(userRepository.findById(viewerId())).thenReturn(Optional.of(viewer));
        when(presentationEnricher.enrich(eq(viewer), anyList(), anyMap(), anySet(), anyMap()))
                .thenReturn(Map.of());
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId(), seriesIds))
                .thenReturn(List.of(unwatched, completed));
        when(metadataRefreshService.getSnapshotsForRead(eq(seriesIds), eq(LocalDate.of(2026, 9, 25))))
                .thenReturn(Map.of("1396", snapshot("1396", 2), "94605", snapshot("94605", 2)));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), seriesIds))
                .thenReturn(List.of());

        SeriesProgressReadResult result = reader.readForSeriesIds(
                viewerId(), List.of("1396", "1396", "94605"));

        assertThat(result.progressBySeriesId()).containsOnlyKeys("1396", "94605");
        assertThat(result.progressBySeriesId().get("1396").watchedEpisodeCount()).isZero();
        assertThat(result.progressBySeriesId().get("94605").watchedPercentage()).isEqualTo(100.0);
        verify(metadataRefreshService).getSnapshotsForRead(seriesIds, LocalDate.of(2026, 9, 25));
        verify(diaryEntryRepository).findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), seriesIds);
    }

    @Test
    void returnsEmptyMapWithoutRepositoryCallsWhenThereAreNoUsableSeriesIds() {
        SeriesProgressReadResult result = reader.readForSeriesIds(
                viewerId(), java.util.Arrays.asList(null, "", "  "));

        assertThat(result.progressBySeriesId()).isEmpty();
        verifyNoInteractions(seriesProgressReadRepository, diaryEntryRepository, metadataRefreshService,
                presentationEnricher, userRepository);
    }

    @Test
    void readsOnlyStartedTargetWithoutPresentationAndUsesSnapshotForConsistentBalance() {
        var ids = List.of("1396");
        var target = row("1396", 2L);
        when(target.getLastWatchedDate()).thenReturn(LocalDate.of(2024, 3, 2));
        when(target.getLastWatchedSeasonNumber()).thenReturn(2);
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId(), ids))
                .thenReturn(List.of(target));
        when(metadataRefreshService.getSnapshotsForRead(ids, LocalDate.of(2026, 9, 25)))
                .thenReturn(Map.of("1396", snapshot("1396", 5)));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), ids))
                .thenReturn(List.of());

        var result = reader.readStartedSeries(viewerId(), "1396").orElseThrow();

        assertThat(result.watchedEpisodeCount()).isEqualTo(2L);
        assertThat(result.totalReleasedEpisodeCount()).isEqualTo(5);
        assertThat(result.watchedPercentage()).isEqualTo(40.0);
        assertThat(result.remainingEpisodeCount()).isEqualTo(3L);
        assertThat(result.lastWatchedDate()).isEqualTo(LocalDate.of(2024, 3, 2));
        assertThat(result.lastWatchedSeasonNumber()).isEqualTo(2);
        verifyNoInteractions(presentationEnricher, userRepository);
    }

    @Test
    void fallsBackWithoutLoadingMetadataForAnUnstartedSeries() {
        var target = mock(SeriesProgressReadRepository.SeriesProgressCandidate.class);
        when(target.getWatchedEpisodeCount()).thenReturn(0L);
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId(), List.of("1396")))
                .thenReturn(List.of(target));
        assertThat(reader.readStartedSeries(viewerId(), "1396")).isEmpty();
        verifyNoInteractions(metadataRefreshService, diaryEntryRepository, presentationEnricher, userRepository);
    }

    @Test
    void keepsObservedHistoryWhenSnapshotsAreUnavailable() {
        var ids = List.of("1396");
        var target = row("1396", 2L);
        var date = LocalDate.of(2024, 3, 2);
        when(target.getLastWatchedDate()).thenReturn(date);
        when(target.getTotalReleasedEpisodeCount()).thenReturn(null);
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId(), ids))
                .thenReturn(List.of(target));
        when(metadataRefreshService.getSnapshotsForRead(ids, LocalDate.of(2026, 9, 25)))
                .thenReturn(Map.of("1396", new SeriesProgressMetadataRefreshService.Snapshot(null, List.of())));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), ids))
                .thenReturn(List.of());

        var result = reader.readStartedSeries(viewerId(), "1396").orElseThrow();

        assertThat(result.watchedEpisodeCount()).isEqualTo(2L);
        assertThat(result.lastWatchedDate()).isEqualTo(date);
        assertThat(result.totalReleasedEpisodeCount()).isNull();
        assertThat(result.remainingEpisodeCount()).isNull();
        assertThat(result.watchedPercentage()).isNull();
        assertThat(result.seasonProgress()).isEmpty();
    }

    @Test
    void derivesRemainingFromTheSelectedMetadataWhenTheProjectionHasAnOlderTotal() {
        var ids = List.of("1396");
        var target = row("1396", 5L);
        when(target.getRemainingEpisodeCount()).thenReturn(2L);
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId(), ids))
                .thenReturn(List.of(target));
        when(metadataRefreshService.getSnapshotsForRead(ids, LocalDate.of(2026, 9, 25)))
                .thenReturn(Map.of("1396", snapshot("1396", 5)));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), ids))
                .thenReturn(List.of());

        var result = reader.readStartedSeries(viewerId(), "1396").orElseThrow();

        assertThat(result.watchedPercentage()).isEqualTo(100.0);
        assertThat(result.remainingEpisodeCount()).isZero();
        assertThat(result.totalReleasedEpisodeCount()).isEqualTo(5);
    }

    private SeriesProgressReadRepository.SeriesProgressCandidate row(String seriesTmdbId, long watchedEpisodeCount) {
        SeriesProgressReadRepository.SeriesProgressCandidate row =
                mock(SeriesProgressReadRepository.SeriesProgressCandidate.class);
        when(row.getSeriesTmdbId()).thenReturn(seriesTmdbId);
        when(row.getWatchedEpisodeCount()).thenReturn(watchedEpisodeCount);
        when(row.getWatchedRuntimeComplete()).thenReturn(true);
        return row;
    }

    private SeriesProgressMetadataRefreshService.Snapshot snapshot(String seriesTmdbId, int totalEpisodeCount) {
        return new SeriesProgressMetadataRefreshService.Snapshot(
                new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                        seriesTmdbId, totalEpisodeCount, null, 0, LocalDate.of(2026, 9, 1),
                        LocalDateTime.of(2026, 9, 1, 0, 0), null),
                List.of());
    }

    private java.util.UUID viewerId() {
        return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
}
