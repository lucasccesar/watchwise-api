package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentViewerStateService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PersonCreditViewerStateResolver {

    private final ContentViewerStateService contentViewerStateService;
    private final DiaryEntryRepository diaryEntryRepository;
    private final SeriesProgressMetadataRepository seriesProgressMetadataRepository;

    public ContentViewerStateService.Resolution resolve(UUID viewerId, List<PersonFilmographyCredit> credits) {
        List<ContentCoordinate> coordinates = credits.stream().map(PersonCreditViewerStateResolver::coordinate)
                .distinct().toList();
        ContentViewerStateService.Resolution common = contentViewerStateService.resolve(viewerId, coordinates, Map.of());
        if (viewerId == null || credits.isEmpty()) {
            return common;
        }

        Set<String> tmdbIds = credits.stream().map(PersonFilmographyCredit::tmdbId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> idList = new ArrayList<>(tmdbIds);
        List<DiaryEntryRepository.PersonCreditMedia> activity = diaryEntryRepository
                .findWatchedMediaForPersonCredits(viewerId, idList);
        Set<String> directlyWatchedSeries = activity.stream()
                .filter(media -> media.getSourceType() == ContentType.SERIES)
                .map(DiaryEntryRepository.PersonCreditMedia::getTmdbId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        List<String> seriesIds = credits.stream().filter(credit -> credit.type() == MovieOrSeriesType.SERIES)
                .map(PersonFilmographyCredit::tmdbId).distinct().toList();
        Map<String, Long> watchedCounts = seriesIds.isEmpty() ? Map.of()
                : diaryEntryRepository.findEpisodeSeriesCountsByUserIdAndSeriesTmdbIdIn(viewerId, seriesIds).stream()
                        .collect(Collectors.toMap(DiaryEntryRepository.SeriesEpisodeCount::getSeriesTmdbId,
                                row -> row.getWatchedEpisodeCount() == null ? 0L : row.getWatchedEpisodeCount(),
                                Long::max));
        Map<String, Integer> releasedCounts = seriesIds.isEmpty() ? Map.of()
                : seriesProgressMetadataRepository.findAllBySeriesTmdbIdIn(seriesIds).stream()
                        .collect(Collectors.toMap(
                                SeriesProgressMetadataRepository.SeriesProgressMetadataProjection::getSeriesTmdbId,
                                SeriesProgressMetadataRepository.SeriesProgressMetadataProjection::getRegularReleasedEpisodeCount,
                                (left, right) -> left));

        Map<ContentCoordinate, ContentViewerStateDTO> resolvedStates = new LinkedHashMap<>();
        for (PersonFilmographyCredit credit : credits) {
            ContentCoordinate coordinate = coordinate(credit);
            ContentViewerStateDTO state = common.statesByCoordinate().get(coordinate);
            if (state == null) state = emptyState();
            if (credit.type() == MovieOrSeriesType.SERIES) {
                long watched = watchedCounts.getOrDefault(credit.tmdbId(), 0L);
                Integer released = releasedCounts.get(credit.tmdbId());
                boolean direct = directlyWatchedSeries.contains(credit.tmdbId());
                boolean hasEpisodeActivity = watched > 0;
                WatchStatus status = direct ? WatchStatus.WATCHED
                        : !hasEpisodeActivity ? WatchStatus.UNWATCHED
                        : released == null ? WatchStatus.UNKNOWN
                        : watched >= released ? WatchStatus.WATCHED : WatchStatus.PARTIALLY_WATCHED;
                Integer watchedEpisodeCount = released == null ? null : Math.toIntExact(watched);
                Integer releasedEpisodeCount = released;
                if (status == WatchStatus.UNKNOWN) {
                    watchedEpisodeCount = null;
                    releasedEpisodeCount = null;
                }
                state = withSeriesProgress(state, status, watchedEpisodeCount, releasedEpisodeCount);
            }
            resolvedStates.put(coordinate, state);
        }
        return new ContentViewerStateService.Resolution(resolvedStates, common.existingContentIdsByCoordinate());
    }

    private static ContentCoordinate coordinate(PersonFilmographyCredit credit) {
        ContentType type = credit.type() == MovieOrSeriesType.MOVIE ? ContentType.MOVIE : ContentType.SERIES;
        return new ContentCoordinate(type, credit.tmdbId(), null, null, null);
    }

    private static ContentViewerStateDTO withSeriesProgress(
            ContentViewerStateDTO state, WatchStatus status, Integer watched, Integer released) {
        return new ContentViewerStateDTO(status, state.myRating(), state.myReview(), state.lastWatchedDate(),
                state.lastWatchNumber(), state.watchedInTheater(), state.playsCount(), state.latestDiaryEntryId(),
                state.inWatchlist(), state.watchlistEntryId(), state.dropped(), state.droppedEntryId(), state.lists(),
                watched, released);
    }

    private static ContentViewerStateDTO emptyState() {
        return new ContentViewerStateDTO(WatchStatus.UNWATCHED, null, null, null, null, null, 0,
                null, false, null, false, null, List.of(), null, null);
    }
}
