package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressAssembler;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressPresentationEnricher;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressPresentationEnricher.Enrichment;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReader;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressReadResult;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SeriesProgressReaderImpl implements SeriesProgressReader {

    private final SeriesProgressReadRepository seriesProgressReadRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final SeriesProgressMetadataRefreshService metadataRefreshService;
    private final SeriesProgressAssembler seriesProgressAssembler;
    private final SeriesProgressPresentationEnricher presentationEnricher;
    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    public SeriesProgressReadResult readForSeriesIds(
            UUID viewerId, Collection<String> seriesTmdbIds) {
        List<String> distinctSeriesIds = normalizeSeriesIds(seriesTmdbIds);
        if (distinctSeriesIds.isEmpty()) {
            return SeriesProgressReadResult.empty();
        }

        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        List<SeriesProgressReadRepository.SeriesProgressCandidate> rows =
                seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(viewerId, distinctSeriesIds);
        Map<String, SeriesProgressReadRepository.SeriesProgressCandidate> rowsBySeriesId = rows.stream()
                .collect(Collectors.toMap(
                        SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId,
                        row -> row));
        List<SeriesProgressReadRepository.SeriesProgressCandidate> orderedRows = distinctSeriesIds.stream()
                .map(rowsBySeriesId::get)
                .filter(Objects::nonNull)
                .toList();
        Map<String, SeriesProgressMetadataRefreshService.Snapshot> snapshots =
                metadataRefreshService.getSnapshotsForRead(distinctSeriesIds, LocalDate.now(clock));
        Map<String, Map<Integer, DiaryEntryRepository.SeasonProgress>> watchedProgress =
                loadWatchedProgress(viewerId, distinctSeriesIds);

        Map<String, SeriesInProgressResponseDTO> baseProgress = new LinkedHashMap<>();
        for (SeriesProgressReadRepository.SeriesProgressCandidate row : orderedRows) {
            baseProgress.put(row.getSeriesTmdbId(), seriesProgressAssembler.toDetailedSeriesResponse(
                    row,
                    snapshots.get(row.getSeriesTmdbId()),
                    watchedProgress.getOrDefault(row.getSeriesTmdbId(), Map.of())));
        }

        Set<WatchedEpisodeCoordinate> watchedCoordinates = diaryEntryRepository
                .findWatchedEpisodeCoordinates(viewerId, distinctSeriesIds);
        Map<String, List<SeasonProgressDTO>> seasonProgressBySeries = baseProgress.values().stream()
                .collect(Collectors.toMap(
                        SeriesInProgressResponseDTO::seriesTmdbId,
                        SeriesInProgressResponseDTO::seasonProgress,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<String, Enrichment> presentation = presentationEnricher.enrich(
                viewer,
                orderedRows,
                seasonProgressBySeries,
                watchedCoordinates == null ? Set.of() : watchedCoordinates,
                Map.of());

        Map<String, SeriesInProgressResponseDTO> result = new LinkedHashMap<>();
        baseProgress.forEach((seriesId, progress) -> {
            Enrichment enrichment = presentation.get(seriesId);
            SeriesInProgressResponseDTO enriched = enrichment == null
                    ? progress
                    : progress.withPresentation(
                            enrichment.seriesTitle(),
                            enrichment.seriesPosterPath(),
                            enrichment.lastWatchedEpisodeTitle(),
                            enrichment.nextEpisode());
            result.put(seriesId, seriesProgressAssembler.splitCompletedSeasonProgress(enriched));
        });
        String firstSeriesId = orderedRows.isEmpty() ? null : orderedRows.getFirst().getSeriesTmdbId();
        Enrichment firstPresentation = firstSeriesId == null ? null : presentation.get(firstSeriesId);
        return new SeriesProgressReadResult(
                result,
                firstPresentation == null ? null : firstPresentation.seriesBackdropPath());
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
