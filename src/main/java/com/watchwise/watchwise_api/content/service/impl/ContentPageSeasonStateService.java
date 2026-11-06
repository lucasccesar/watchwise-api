package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotRepository;
import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentPageSeasonStateService {

    private final DiaryEntryRepository diaryEntryRepository;
    private final CalendarScheduleCompletenessRepository completenessRepository;
    private final CalendarScheduleSnapshotRepository snapshotRepository;
    private final CalendarScheduleRefreshService scheduleRefreshService;
    private final Clock clock;

    public Map<ContentCoordinate, ContentViewerStateDTO> resolve(
            UUID viewerId,
            String seriesTmdbId,
            String region,
            String language,
            Collection<ContentCoordinate> seasonCoordinates,
            Collection<SeasonSummaryDTO> seasonSummaries,
            Map<ContentCoordinate, ContentViewerStateDTO> existingStates) {
        List<ContentCoordinate> coordinates = seasonCoordinates == null
                ? List.of()
                : seasonCoordinates.stream()
                        .filter(Objects::nonNull)
                        .filter(coordinate -> coordinate.type() == com.watchwise.watchwise_api.content.entity.ContentType.SEASON)
                        .distinct()
                        .toList();
        if (coordinates.isEmpty() || seriesTmdbId == null || seriesTmdbId.isBlank()) {
            return Map.of();
        }

        Map<Integer, ContentViewerStateDTO> statesBySeason = new LinkedHashMap<>();
        Map<ContentCoordinate, ContentViewerStateDTO> stateByCoordinate = existingStates == null
                ? Map.of()
                : existingStates;
        for (ContentCoordinate coordinate : coordinates) {
            statesBySeason.putIfAbsent(coordinate.seasonNumber(), stateByCoordinate.get(coordinate));
        }

        List<DiaryEntryRepository.WatchedEpisodeCoordinateProjection> watchedRows = diaryEntryRepository
                .findWatchedEpisodeCoordinatesByUserIdAndSeriesIdsOrSeriesSeasonPairs(
                        viewerId, List.of(seriesTmdbId), List.of());
        Map<Integer, Set<Integer>> watchedNumbersBySeason = watchedRows == null
                ? Map.of()
                : watchedRows.stream()
                        .filter(Objects::nonNull)
                        .filter(row -> row.getSeasonNumber() != null && row.getSeasonNumber() > 0)
                        .filter(row -> row.getEpisodeNumber() != null && row.getEpisodeNumber() > 0)
                        .collect(Collectors.groupingBy(
                                DiaryEntryRepository.WatchedEpisodeCoordinateProjection::getSeasonNumber,
                                LinkedHashMap::new,
                                Collectors.mapping(
                                        DiaryEntryRepository.WatchedEpisodeCoordinateProjection::getEpisodeNumber,
                                        Collectors.toCollection(LinkedHashSet::new))));
        Map<Integer, LocalDate> latestDateBySeason = latestDatesBySeason(viewerId, seriesTmdbId);

        Set<Integer> seasonsNeedingSchedule = coordinates.stream()
                .map(ContentCoordinate::seasonNumber)
                .filter(Objects::nonNull)
                .filter(season -> !watchedNumbersBySeason.getOrDefault(season, Set.of()).isEmpty())
                .filter(season -> statesBySeason.get(season) == null
                        || statesBySeason.get(season).watchStatus() != WatchStatus.WATCHED)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Integer, CalendarScheduleCompleteness> markers = Map.of();
        Map<Integer, List<CalendarScheduleSnapshot>> snapshots = Map.of();
        if (!seasonsNeedingSchedule.isEmpty()) {
            List<Integer> seasonNumbers = seasonsNeedingSchedule.stream().toList();
            markers = completenessRepository.findByGroupTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                            CalendarScheduleCompleteness.GroupType.SEASON,
                            seriesTmdbId, seasonNumbers, region, language)
                    .stream()
                    .filter(marker -> marker.getSeasonNumber() != null)
                    .collect(Collectors.toMap(
                            CalendarScheduleCompleteness::getSeasonNumber,
                            marker -> marker,
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            snapshots = snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberInAndRegionAndLanguage(
                            CalendarScheduleSnapshot.EventType.EPISODE, seriesTmdbId, seasonNumbers, region, language)
                    .stream()
                    .collect(Collectors.groupingBy(CalendarScheduleSnapshot::getSeasonNumber));
        }

        Map<Integer, Integer> expectedCounts = expectedEpisodeCounts(seasonSummaries);
        LocalDate today = LocalDate.now(clock);
        LocalDateTime checkedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<ContentCoordinate, ContentViewerStateDTO> result = new LinkedHashMap<>();
        for (ContentCoordinate coordinate : coordinates) {
            Integer seasonNumber = coordinate.seasonNumber();
            ContentViewerStateDTO base = stateByCoordinate.get(coordinate);
            if (base == null) {
                base = emptyState();
            }
            Set<Integer> watchedEpisodeNumbers = watchedNumbersBySeason.getOrDefault(seasonNumber, Set.of());
            LocalDate lastWatchedDate = latestDateBySeason.getOrDefault(seasonNumber, base.lastWatchedDate());

            if (base.watchStatus() == WatchStatus.WATCHED) {
                result.put(coordinate, copyWithWatchState(
                        base, WatchStatus.WATCHED, lastWatchedDate,
                        base.watchedEpisodeCount(), base.releasedEpisodeCount()));
                continue;
            }
            if (watchedEpisodeNumbers.isEmpty()) {
                result.put(coordinate, copyWithWatchState(base, WatchStatus.UNWATCHED, null, 0, null));
                continue;
            }

            CalendarScheduleCompleteness marker = markers.get(seasonNumber);
            List<CalendarScheduleSnapshot> seasonSnapshots = snapshots.getOrDefault(seasonNumber, List.of());
            Integer expectedEpisodeCount = expectedCounts.get(seasonNumber);
            if (!isTrustedSchedule(marker, seasonSnapshots, expectedEpisodeCount, checkedAt)) {
                scheduleRefreshService.refreshSeasonInBackground(seriesTmdbId, seasonNumber, region, language);
                result.put(coordinate, copyWithWatchState(base, WatchStatus.UNKNOWN, lastWatchedDate, null, null));
                continue;
            }

            Set<Integer> releasedEpisodeNumbers = seasonSnapshots.stream()
                    .filter(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot()))
                    .filter(snapshot -> snapshot.getSeasonNumber() != null
                            && snapshot.getSeasonNumber().equals(seasonNumber))
                    .filter(snapshot -> snapshot.getEpisodeNumber() != null && snapshot.getEpisodeNumber() > 0)
                    .filter(snapshot -> snapshot.getReleaseDate() != null
                            && !snapshot.getReleaseDate().isAfter(today))
                    .map(CalendarScheduleSnapshot::getEpisodeNumber)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (releasedEpisodeNumbers.isEmpty()) {
                result.put(coordinate, copyWithWatchState(base, WatchStatus.UNKNOWN, lastWatchedDate, null, null));
                continue;
            }

            long watchedReleasedCount = releasedEpisodeNumbers.stream().filter(watchedEpisodeNumbers::contains).count();
            WatchStatus status = watchedReleasedCount == 0
                    ? WatchStatus.UNWATCHED
                    : watchedReleasedCount == releasedEpisodeNumbers.size()
                            ? WatchStatus.WATCHED
                            : WatchStatus.PARTIALLY_WATCHED;
            result.put(coordinate, copyWithWatchState(
                    base, status, lastWatchedDate, (int) watchedReleasedCount, releasedEpisodeNumbers.size()));
        }
        return Map.copyOf(result);
    }

    private Map<Integer, LocalDate> latestDatesBySeason(UUID viewerId, String seriesTmdbId) {
        List<DiaryEntryRepository.SeasonViewerHistoryDate> rows =
                diaryEntryRepository.findSeasonViewerHistoryDates(viewerId, seriesTmdbId);
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getSeasonNumber() != null && row.getSeasonNumber() > 0)
                .filter(row -> row.getLastWatchedDate() != null)
                .collect(Collectors.toMap(
                        DiaryEntryRepository.SeasonViewerHistoryDate::getSeasonNumber,
                        DiaryEntryRepository.SeasonViewerHistoryDate::getLastWatchedDate,
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    private Map<Integer, Integer> expectedEpisodeCounts(Collection<SeasonSummaryDTO> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return Map.of();
        }
        return summaries.stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.seasonNumber() != null && summary.seasonNumber() > 0)
                .filter(summary -> summary.episodeCount() != null && summary.episodeCount() >= 0)
                .collect(Collectors.toMap(
                        SeasonSummaryDTO::seasonNumber,
                        SeasonSummaryDTO::episodeCount,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    private boolean isTrustedSchedule(
            CalendarScheduleCompleteness marker,
            List<CalendarScheduleSnapshot> snapshots,
            Integer expectedEpisodeCount,
            LocalDateTime checkedAt) {
        if (marker == null || !Boolean.TRUE.equals(marker.getComplete())
                || expectedEpisodeCount == null
                || !Objects.equals(marker.getExpectedEpisodeCount(), expectedEpisodeCount)) {
            return false;
        }
        List<CalendarScheduleSnapshot> presentSnapshots = snapshots.stream()
                .filter(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot()))
                .filter(snapshot -> snapshot.getEpisodeNumber() != null && snapshot.getEpisodeNumber() > 0)
                .toList();
        Set<Integer> episodeNumbers = presentSnapshots.stream()
                .map(CalendarScheduleSnapshot::getEpisodeNumber)
                .collect(Collectors.toSet());
        return presentSnapshots.size() == expectedEpisodeCount
                && episodeNumbers.size() == expectedEpisodeCount
                && presentSnapshots.stream().allMatch(snapshot -> snapshot.getReleaseDate() != null
                        && snapshot.getNextCheckAt() != null
                        && snapshot.getNextCheckAt().isAfter(checkedAt));
    }

    private ContentViewerStateDTO copyWithWatchState(
            ContentViewerStateDTO base,
            WatchStatus watchStatus,
            LocalDate lastWatchedDate,
            Integer watchedEpisodeCount,
            Integer releasedEpisodeCount) {
        return new ContentViewerStateDTO(
                watchStatus,
                base.myRating(),
                base.myReview(),
                lastWatchedDate,
                base.lastWatchNumber(),
                base.watchedInTheater(),
                base.playsCount(),
                base.latestDiaryEntryId(),
                base.inWatchlist(),
                base.watchlistEntryId(),
                base.dropped(),
                base.droppedEntryId(),
                base.lists(),
                watchedEpisodeCount,
                releasedEpisodeCount,
                base.currentRatingVersion(),
                base.reviewsCount(),
                base.reviewIds());
    }

    private ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(
                WatchStatus.UNWATCHED,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                false,
                null,
                false,
                null,
                List.of(),
                null,
                null,
                null,
                0,
                List.of());
    }
}
