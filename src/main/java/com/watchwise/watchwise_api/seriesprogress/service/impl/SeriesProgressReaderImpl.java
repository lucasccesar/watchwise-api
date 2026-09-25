package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressAssembler;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeriesProgressReaderImpl implements SeriesProgressReader {

    private final SeriesProgressReadRepository seriesProgressReadRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final SeriesProgressMetadataRefreshService metadataRefreshService;
    private final SeriesProgressAssembler seriesProgressAssembler;
    private final Clock clock;

    @Override
    public Map<String, SeriesInProgressResponseDTO> readForSeriesIds(
            UUID viewerId, Collection<String> seriesTmdbIds) {
        List<String> distinctSeriesIds = normalizeSeriesIds(seriesTmdbIds);
        if (distinctSeriesIds.isEmpty()) {
            return Map.of();
        }

        List<SeriesProgressReadRepository.SeriesProgressCandidate> rows =
                seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId, distinctSeriesIds);
        Map<String, SeriesProgressMetadataRefreshService.Snapshot> snapshots =
                metadataRefreshService.getSnapshotsForRead(distinctSeriesIds, LocalDate.now(clock));
        Map<String, Map<Integer, DiaryEntryRepository.SeasonProgress>> watchedProgress =
                loadWatchedProgress(viewerId, distinctSeriesIds);

        Map<String, SeriesInProgressResponseDTO> result = new LinkedHashMap<>();
        for (SeriesProgressReadRepository.SeriesProgressCandidate row : rows) {
            result.put(
                    row.getSeriesTmdbId(),
                    seriesProgressAssembler.toDetailedSeriesResponse(
                            row,
                            snapshots.get(row.getSeriesTmdbId()),
                            watchedProgress.getOrDefault(row.getSeriesTmdbId(), Map.of())));
        }
        return Collections.unmodifiableMap(result);
    }

    private List<String> normalizeSeriesIds(Collection<String> seriesTmdbIds) {
        if (seriesTmdbIds == null) {
            return List.of();
        }
        return seriesTmdbIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    }

    private Map<String, Map<Integer, DiaryEntryRepository.SeasonProgress>> loadWatchedProgress(
            UUID viewerId, List<String> seriesTmdbIds) {
        List<DiaryEntryRepository.SeasonProgress> progress = diaryEntryRepository
                .findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(viewerId, seriesTmdbIds);
        return progress.stream().collect(Collectors.groupingBy(
                DiaryEntryRepository.SeasonProgress::getSeriesTmdbId,
                LinkedHashMap::new,
                Collectors.toMap(
                        DiaryEntryRepository.SeasonProgress::getSeasonNumber,
                        row -> row,
                        (first, second) -> first,
                        LinkedHashMap::new)));
    }
}
