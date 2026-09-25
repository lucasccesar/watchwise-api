package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressAssembler;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesProgressReaderImplTest {

    @Mock
    private SeriesProgressReadRepository seriesProgressReadRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private SeriesProgressMetadataRefreshService metadataRefreshService;

    private SeriesProgressReaderImpl reader;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T03:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        reader = new SeriesProgressReaderImpl(
                seriesProgressReadRepository,
                diaryEntryRepository,
                metadataRefreshService,
                new SeriesProgressAssembler(),
                clock);
    }

    @Test
    void loadsOneSnapshotBatchForDistinctSeriesIdsAndReturnsAllSeries() {
        List<String> seriesIds = List.of("1396", "94605");
        SeriesProgressReadRepository.SeriesProgressCandidate unwatched = row("1396", 0L);
        SeriesProgressReadRepository.SeriesProgressCandidate completed = row("94605", 2L);
        when(seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId(), seriesIds))
                .thenReturn(List.of(unwatched, completed));
        when(metadataRefreshService.getSnapshotsForRead(eq(seriesIds), eq(LocalDate.of(2026, 9, 25))))
                .thenReturn(Map.of("1396", snapshot("1396", 2), "94605", snapshot("94605", 2)));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), seriesIds))
                .thenReturn(List.of());

        Map<String, SeriesInProgressResponseDTO> result = reader.readForSeriesIds(
                viewerId(), List.of("1396", "1396", "94605"));

        assertThat(result).containsOnlyKeys("1396", "94605");
        assertThat(result.get("1396").watchedEpisodeCount()).isZero();
        assertThat(result.get("94605").watchedPercentage()).isEqualTo(100.0);
        verify(metadataRefreshService).getSnapshotsForRead(seriesIds, LocalDate.of(2026, 9, 25));
        verify(diaryEntryRepository).findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId(), seriesIds);
    }

    @Test
    void returnsEmptyMapWithoutRepositoryCallsWhenThereAreNoUsableSeriesIds() {
        Map<String, SeriesInProgressResponseDTO> result = reader.readForSeriesIds(
                viewerId(), java.util.Arrays.asList(null, "", "  "));

        assertThat(result).isEmpty();
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
