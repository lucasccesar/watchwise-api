package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeriesProgressAssemblerTest {

    private final SeriesProgressAssembler assembler = new SeriesProgressAssembler();

    @Test
    void returnsZeroProgressForAListedSeriesWithoutWatchedEpisodes() {
        SeriesInProgressResponseDTO result = assembler.toDetailedSeriesResponse(
                row("1396", 0L, null, null, null),
                snapshotWithReleasedSeasons(2, 10),
                Map.of());

        assertThat(result.watchedEpisodeCount()).isZero();
        assertThat(result.totalEpisodeCount()).isEqualTo(10);
        assertThat(result.watchedPercentage()).isZero();
        assertThat(result.seasonProgress()).hasSize(1);
        assertThat(result.seasonProgress().getFirst().watchedEpisodeCount()).isZero();
    }

    @Test
    void returnsOneHundredPercentForACompletedListedSeries() {
        SeriesInProgressResponseDTO result = assembler.toDetailedSeriesResponse(
                row("1396", 10L, 600L, 2, 5),
                snapshotWithReleasedSeasons(2, 10),
                Map.of(1, seasonProgress("1396", 1, 5L, 300L)));

        assertThat(result.watchedPercentage()).isEqualTo(100.0);
        assertThat(result.remainingEpisodeCount()).isZero();
        assertThat(result.remainingRuntimeMinutes()).isEqualTo(400L);
    }

    @Test
    void returnsNullRemainingRuntimeWhenTheWatchedRuntimeIsIncomplete() {
        SeasonProgressDTO result = assembler.toSeasonProgress(
                seasonSnapshot(1, 5, 250),
                watchedProgress(5L, 200L, false));

        assertThat(result.totalEpisodeCount()).isEqualTo(5);
        assertThat(result.watchedEpisodeCount()).isEqualTo(5L);
        assertThat(result.watchedRuntimeMinutes()).isEqualTo(200L);
        assertThat(result.remainingRuntimeMinutes()).isNull();
    }

    @Test
    void returnsNullPercentageWhenThereIsNoReleasedEpisodeTotal() {
        SeriesInProgressResponseDTO result = assembler.toDetailedSeriesResponse(
                row("1396", 2L, null, 1, 2),
                null,
                Map.of());

        assertThat(result.totalEpisodeCount()).isNull();
        assertThat(result.watchedPercentage()).isNull();
        assertThat(result.remainingEpisodeCount()).isNull();
        assertThat(result.seasonProgress()).isEmpty();
    }

    private SeriesProgressReadRepository.SeriesProgressCandidate row(
            String seriesTmdbId, Long watchedEpisodeCount, Long watchedRuntimeMinutes,
            Integer maxSeasonNumber, Integer maxEpisodeNumber) {
        SeriesProgressReadRepository.SeriesProgressCandidate row =
                mock(SeriesProgressReadRepository.SeriesProgressCandidate.class);
        when(row.getSeriesTmdbId()).thenReturn(seriesTmdbId);
        when(row.getWatchedEpisodeCount()).thenReturn(watchedEpisodeCount);
        when(row.getWatchedRuntimeMinutes()).thenReturn(watchedRuntimeMinutes);
        when(row.getWatchedRuntimeComplete()).thenReturn(watchedRuntimeMinutes != null);
        when(row.getMaxSeasonNumber()).thenReturn(maxSeasonNumber);
        when(row.getMaxEpisodeNumber()).thenReturn(maxEpisodeNumber);
        when(row.getLastWatchedSeasonNumber()).thenReturn(maxSeasonNumber);
        when(row.getLastWatchedEpisodeNumber()).thenReturn(maxEpisodeNumber);
        when(row.getLastWatchedDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(row.getTotalReleasedEpisodeCount()).thenReturn(null);
        when(row.getTotalKnownRuntime()).thenReturn(null);
        when(row.getLastReleasedEpisodeDate()).thenReturn(null);
        when(row.getRemainingEpisodeCount()).thenReturn(null);
        when(row.getRemainingRuntimeMinutes()).thenReturn(null);
        return row;
    }

    private SeriesProgressMetadataRefreshService.Snapshot snapshotWithReleasedSeasons(
            int seasonNumber, int totalEpisodeCount) {
        return new SeriesProgressMetadataRefreshService.Snapshot(
                new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                        "1396", totalEpisodeCount, 1000, 10, LocalDate.of(2026, 9, 1),
                        LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0)),
                List.of(seasonSnapshot(seasonNumber, totalEpisodeCount, 500)));
    }

    private SeriesProgressMetadataRefreshService.SeasonSnapshot seasonSnapshot(
            int seasonNumber, int totalEpisodeCount, Integer totalRuntime) {
        return new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                "1396", seasonNumber, totalEpisodeCount, totalRuntime, totalRuntime == null ? 0 : totalEpisodeCount,
                LocalDate.of(2026, 9, 1), LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    private DiaryEntryRepository.SeasonProgress watchedProgress(
            long watchedEpisodeCount, Long watchedRuntimeMinutes, boolean runtimeComplete) {
        DiaryEntryRepository.SeasonProgress progress = mock(DiaryEntryRepository.SeasonProgress.class);
        when(progress.getSeriesTmdbId()).thenReturn("1396");
        when(progress.getSeasonNumber()).thenReturn(1);
        when(progress.getWatchedEpisodeCount()).thenReturn(watchedEpisodeCount);
        when(progress.getWatchedRuntimeMinutes()).thenReturn(watchedRuntimeMinutes);
        when(progress.getWatchedRuntimeComplete()).thenReturn(runtimeComplete);
        return progress;
    }

    private DiaryEntryRepository.SeasonProgress seasonProgress(
            String seriesTmdbId, int seasonNumber, long watchedEpisodeCount, long watchedRuntimeMinutes) {
        DiaryEntryRepository.SeasonProgress progress = watchedProgress(
                watchedEpisodeCount, watchedRuntimeMinutes, true);
        when(progress.getSeriesTmdbId()).thenReturn(seriesTmdbId);
        when(progress.getSeasonNumber()).thenReturn(seasonNumber);
        return progress;
    }
}
