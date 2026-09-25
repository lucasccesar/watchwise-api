package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class SeriesProgressAssembler {

    public SeriesInProgressResponseDTO toDetailedSeriesResponse(
            SeriesProgressReadRepository.SeriesProgressCandidate row,
            SeriesProgressMetadataRefreshService.Snapshot snapshot,
            Map<Integer, DiaryEntryRepository.SeasonProgress> watchedProgress) {
        SeriesProgressMetadataRefreshService.SeriesSnapshot series = snapshot == null
                ? null
                : snapshot.series();
        Integer totalReleasedEpisodeCount = series != null
                ? series.regularReleasedEpisodeCount()
                : row.getTotalReleasedEpisodeCount();
        Integer totalKnownRuntime = series != null ? series.totalKnownRuntime() : row.getTotalKnownRuntime();
        Long watchedEpisodeCount = valueOrZero(row.getWatchedEpisodeCount());
        Long watchedRuntimeMinutes = row.getWatchedRuntimeMinutes();
        Long remainingEpisodeCount = row.getRemainingEpisodeCount() != null
                ? row.getRemainingEpisodeCount()
                : remainingEpisodes(totalReleasedEpisodeCount, watchedEpisodeCount);
        Long remainingRuntimeMinutes = remainingRuntime(
                totalKnownRuntime, watchedRuntimeMinutes, row.getWatchedRuntimeComplete());

        List<SeasonProgressDTO> seasonProgress = snapshot == null
                ? List.of()
                : snapshot.seasons().stream()
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                .filter(season -> season.regularReleasedEpisodeCount() != null
                        && season.regularReleasedEpisodeCount() > 0)
                .sorted(Comparator.comparing(SeriesProgressMetadataRefreshService.SeasonSnapshot::seasonNumber))
                .map(season -> toSeasonProgress(season, watchedProgress.get(season.seasonNumber())))
                .toList();

        return new SeriesInProgressResponseDTO(
                row.getSeriesTmdbId(),
                row.getMaxSeasonNumber(),
                row.getMaxEpisodeNumber(),
                row.getLastWatchedDate(),
                watchedEpisodeCount,
                totalReleasedEpisodeCount,
                percentage(watchedEpisodeCount, totalReleasedEpisodeCount),
                seasonProgress,
                row.getLastWatchedSeasonNumber(),
                row.getLastWatchedEpisodeNumber(),
                watchedRuntimeMinutes,
                totalReleasedEpisodeCount,
                totalKnownRuntime,
                series != null ? series.lastReleasedEpisodeDate() : row.getLastReleasedEpisodeDate(),
                remainingEpisodeCount,
                remainingRuntimeMinutes);
    }

    public SeasonProgressDTO toSeasonProgress(
            SeriesProgressMetadataRefreshService.SeasonSnapshot season,
            DiaryEntryRepository.SeasonProgress watched) {
        Long watchedEpisodeCount = watched == null ? 0L : valueOrZero(watched.getWatchedEpisodeCount());
        Long watchedRuntimeMinutes = watched == null
                ? Long.valueOf(0L)
                : watched.getWatchedRuntimeMinutes();
        Integer totalEpisodeCount = season.regularReleasedEpisodeCount();
        return new SeasonProgressDTO(
                season.seasonNumber(),
                watchedEpisodeCount,
                totalEpisodeCount,
                percentage(watchedEpisodeCount, totalEpisodeCount),
                watchedRuntimeMinutes,
                remainingEpisodes(totalEpisodeCount, watchedEpisodeCount),
                remainingRuntime(season.totalKnownRuntime(), watchedRuntimeMinutes,
                        watched == null || Boolean.TRUE.equals(watched.getWatchedRuntimeComplete())));
    }

    private Long remainingEpisodes(Integer totalEpisodeCount, Long watchedEpisodeCount) {
        return totalEpisodeCount == null
                ? null
                : Math.max(totalEpisodeCount.longValue() - valueOrZero(watchedEpisodeCount), 0L);
    }

    private Long remainingRuntime(
            Integer totalRuntimeMinutes, Long watchedRuntimeMinutes, Boolean watchedRuntimeComplete) {
        return totalRuntimeMinutes == null
                || !Boolean.TRUE.equals(watchedRuntimeComplete)
                || watchedRuntimeMinutes == null
                ? null
                : Math.max(totalRuntimeMinutes.longValue() - watchedRuntimeMinutes, 0L);
    }

    private Double percentage(Long watchedEpisodeCount, Integer totalEpisodeCount) {
        if (totalEpisodeCount == null || totalEpisodeCount <= 0) {
            return null;
        }
        return Math.min(100.0, valueOrZero(watchedEpisodeCount) * 100.0 / totalEpisodeCount);
    }

    private long valueOrZero(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
