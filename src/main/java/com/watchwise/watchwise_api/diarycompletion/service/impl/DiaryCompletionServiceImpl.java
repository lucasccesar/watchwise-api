package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.model.CompletedPass;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeWatch;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionCalculator;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diarycompletion.service.DiaryCompletionService;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.StaleObjectStateException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiaryCompletionServiceImpl implements DiaryCompletionService {

    private final CompletionScopeLoader scopeLoader;
    private final CompletionCalculator completionCalculator;
    private final UserSeriesHistoryStateRepository historyStateRepository;
    private final DiaryCompletionProjectionRepository projectionRepository;
    private final CalendarScheduleCompletenessRepository completenessRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final UserContentMutationLock userContentMutationLock;
    private final NewTransactionExecutor newTransactionExecutor;

    @Override
    public void refreshStaleScopes(UUID ownerId, Instant now) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        List<String> seriesIds = diaryEntryRepository.findSeriesTmdbIdsForDiaryView(ownerId).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (seriesIds.isEmpty()) {
            return;
        }

        Map<String, UserSeriesHistoryState> states = historyStateRepository
                .findByUserIdAndSeriesTmdbIdIn(ownerId, seriesIds).stream()
                .collect(Collectors.toMap(state -> state.getId().getSeriesTmdbId(), Function.identity()));
        Map<String, CalendarScheduleCompleteness> seriesMarkers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SERIES,
                        seriesIds,
                        owner.getPreferredRegion(),
                        owner.getPreferredLanguage())
                .stream()
                .collect(Collectors.toMap(CalendarScheduleCompleteness::getSeriesTmdbId, Function.identity()));
        Map<String, List<CalendarScheduleCompleteness>> seasonMarkers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SEASON,
                        seriesIds,
                        owner.getPreferredRegion(),
                        owner.getPreferredLanguage())
                .stream()
                .collect(Collectors.groupingBy(CalendarScheduleCompleteness::getSeriesTmdbId));
        Set<String> seasonProjectionSeriesIds = staleSeasonProjectionSeriesIds(ownerId, seasonMarkers);

        for (String seriesId : seriesIds) {
            UserSeriesHistoryState state = states.get(seriesId);
            long historyRevision = state == null ? 0L : state.getHistoryRevision();
            long seriesScheduleVersion = scopeVersion(seriesMarkers.get(seriesId));
            if (!isStale(state, historyRevision, seriesScheduleVersion, now)
                    && !seasonProjectionSeriesIds.contains(seriesId)) {
                continue;
            }
            refreshSeriesWithRetry(ownerId, seriesId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
        }
    }

    private void refreshSeriesWithRetry(
            UUID ownerId,
            String seriesId,
            String region,
            String language,
            Instant now) {
        for (int attempt = 0; attempt < 2; attempt++) {
            UserSeriesHistoryState state = historyStateRepository.findById(
                    new UserSeriesHistoryState.Key(ownerId, seriesId)).orElse(null);
            long historyRevision = state == null ? 0L : state.getHistoryRevision();
            CalendarScheduleCompleteness seriesMarker = completenessRepository.findSeriesIdentity(
                    seriesId, region, language).orElse(null);
            List<CalendarScheduleCompleteness> seasonMarkers = completenessRepository
                    .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                            CalendarScheduleCompleteness.GroupType.SEASON,
                            List.of(seriesId), region, language);
            long seriesScheduleVersion = scopeVersion(seriesMarker);
            if (!isStale(state, historyRevision, seriesScheduleVersion, now)
                    && !hasStaleSeasonProjection(ownerId, seriesId, seasonMarkers)) {
                return;
            }

            List<DiaryEntryRepository.EpisodeCompletionWatch> watches = diaryEntryRepository
                    .findEpisodeCompletionWatches(ownerId, List.of(seriesId));
            LoadedScopes loaded = loadScopes(
                    seriesId, watches, region, language, now, seasonMarkers, seriesMarker);
            try {
                if (publish(ownerId, seriesId, historyRevision, loaded, now)) {
                    return;
                }
            } catch (OptimisticLockingFailureException | StaleObjectStateException conflict) {
                // The next bounded attempt rereads both the revision and episode snapshot.
            }
        }
        throw new ConflictException("Diary completion is being refreshed concurrently");
    }

    private Set<String> staleSeasonProjectionSeriesIds(
            UUID ownerId,
            Map<String, List<CalendarScheduleCompleteness>> seasonMarkers) {
        return projectionRepository.findSeasonProjectionVersions(ownerId).stream()
                .filter(projection -> hasStaleSeasonProjection(projection, seasonMarkers))
                .map(DiaryCompletionProjectionRepository.SeasonProjectionVersion::getSeriesTmdbId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private boolean hasStaleSeasonProjection(
            UUID ownerId,
            String seriesId,
            List<CalendarScheduleCompleteness> seasonMarkers) {
        return projectionRepository.findSeasonProjectionVersions(ownerId).stream()
                .filter(projection -> Objects.equals(seriesId, projection.getSeriesTmdbId()))
                .anyMatch(projection -> hasStaleSeasonProjection(projection, Map.of(seriesId, seasonMarkers)));
    }

    private boolean hasStaleSeasonProjection(
            DiaryCompletionProjectionRepository.SeasonProjectionVersion projection,
            Map<String, List<CalendarScheduleCompleteness>> seasonMarkers) {
        CalendarScheduleCompleteness marker = seasonMarkers.getOrDefault(projection.getSeriesTmdbId(), List.of()).stream()
                .filter(candidate -> Objects.equals(candidate.getSeasonNumber(), projection.getSeasonNumber()))
                .findFirst()
                .orElse(null);
        return marker == null
                ? !Objects.equals(projection.getScheduleVersion(), 0L)
                : !Objects.equals(projection.getScheduleVersion(), scopeVersion(marker));
    }

    @Override
    public boolean isComplete(UUID ownerId, MediaReference reference, int watchNumber) {
        Content content = contentFor(reference).orElse(null);
        if (content == null) {
            return false;
        }
        return projectionRepository.findByUserIdAndContentIdAndWatchNumber(ownerId, content.getId(), watchNumber)
                .stream()
                .anyMatch(projection -> projection.getValidUntil() != null
                        && projection.getValidUntil().isAfter(Instant.now()));
    }

    private java.util.Optional<Content> contentFor(MediaReference reference) {
        return switch (reference.type()) {
            case SERIES -> contentRepository.findByTmdbIdAndType(reference.tmdbId(), ContentType.SERIES);
            case SEASON -> contentRepository.findByTypeAndSeriesTmdbIdAndSeasonNumberIn(
                    ContentType.SEASON, reference.seriesTmdbId(), List.of(reference.seasonNumber())).stream().findFirst();
            default -> java.util.Optional.empty();
        };
    }

    private boolean isStale(
            UserSeriesHistoryState state,
            long historyRevision,
            long scheduleVersion,
            Instant now) {
        return state == null
                || !Objects.equals(state.getComputedRevision(), historyRevision)
                || !Objects.equals(state.getScheduleVersion(), scheduleVersion)
                || state.getValidUntil() == null
                || !state.getValidUntil().isAfter(now);
    }

    private LoadedScopes loadScopes(
            String seriesId,
            List<DiaryEntryRepository.EpisodeCompletionWatch> rows,
            String region,
            String language,
            Instant now,
            List<CalendarScheduleCompleteness> seasonMarkers,
            CalendarScheduleCompleteness seriesMarker) {
        VersionedCompletionScope series = scopeLoader.load(MediaReference.series(seriesId), region, language, now);
        Map<Integer, VersionedCompletionScope> seasons = new LinkedHashMap<>();
        rows.stream()
                .map(DiaryEntryRepository.EpisodeCompletionWatch::getSeasonNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(season -> seasons.put(
                        season,
                        scopeLoader.load(MediaReference.season(seriesId, season), region, language, now)));
        Map<Integer, Long> seasonVersions = seasonMarkers.stream()
                .collect(Collectors.toMap(CalendarScheduleCompleteness::getSeasonNumber, this::scopeVersion));
        List<EpisodeWatch> watches = rows.stream().map(this::toEpisodeWatch).toList();
        Map<Integer, List<EpisodeWatch>> watchesBySeason = watches.stream()
                .collect(Collectors.groupingBy(watch -> watch.episode().seasonNumber()));
        return new LoadedScopes(
                series,
                seasons,
                watches,
                watchesBySeason,
                scopeVersion(seriesMarker),
                seasonVersions,
                region,
                language);
    }

    private boolean publish(
            UUID ownerId,
            String seriesId,
            long expectedHistoryRevision,
            LoadedScopes loaded,
            Instant now) {
        return newTransactionExecutor.runInNewTransaction(() -> {
            userContentMutationLock.lock(ownerId);
            UserSeriesHistoryState.Key key = new UserSeriesHistoryState.Key(ownerId, seriesId);
            UserSeriesHistoryState state = historyStateRepository.findById(key).orElse(null);
            if ((state == null && expectedHistoryRevision != 0L)
                    || (state != null && !Objects.equals(state.getHistoryRevision(), expectedHistoryRevision))) {
                return false;
            }
            CalendarScheduleCompleteness currentSeriesMarker = completenessRepository.findSeriesIdentity(
                    seriesId, loaded.region(), loaded.language()).orElse(null);
            if (scopeVersion(currentSeriesMarker) != loaded.seriesVersion()) {
                return false;
            }
            Map<Integer, Long> currentSeasonVersions = completenessRepository
                    .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                            CalendarScheduleCompleteness.GroupType.SEASON,
                            List.of(seriesId), loaded.region(), loaded.language())
                    .stream()
                    .collect(Collectors.toMap(CalendarScheduleCompleteness::getSeasonNumber, this::scopeVersion));
            if (loaded.seasonVersions().entrySet().stream()
                    .anyMatch(entry -> !Objects.equals(entry.getValue(), currentSeasonVersions.getOrDefault(entry.getKey(), 0L)))) {
                return false;
            }

            List<Content> contents = new ArrayList<>();
            contentRepository.findByTmdbIdAndType(seriesId, ContentType.SERIES).ifPresent(contents::add);
            contents.addAll(contentRepository.findByTypeAndSeriesTmdbIdIn(ContentType.SEASON, List.of(seriesId)));
            contents.forEach(content -> projectionRepository.deleteByUserIdAndContentId(ownerId, content.getId()));
            projectionRepository.flush();

            List<DiaryCompletionProjection> projections = new ArrayList<>();
            Content seriesContent = contents.stream()
                    .filter(content -> content.getType() == ContentType.SERIES)
                    .findFirst().orElse(null);
            if (seriesContent != null) {
                completionCalculator.calculate(loaded.series.scope(), loaded.watches).forEach(pass ->
                        projections.add(projection(ownerId, seriesContent, pass, expectedHistoryRevision, loaded.seriesVersion,
                                loaded.series.validUntil())));
            }
            Map<Integer, Content> seasonsByNumber = contents.stream()
                    .filter(content -> content.getType() == ContentType.SEASON)
                    .collect(Collectors.toMap(Content::getSeasonNumber, Function.identity(), (left, right) -> left));
            loaded.seasons.forEach((seasonNumber, scope) -> {
                Content seasonContent = seasonsByNumber.get(seasonNumber);
                if (seasonContent == null) {
                    return;
                }
                completionCalculator.calculate(scope.scope(), loaded.watchesBySeason
                                .getOrDefault(seasonNumber, List.of()))
                        .forEach(pass -> projections.add(projection(ownerId, seasonContent, pass, expectedHistoryRevision,
                                loaded.seasonVersions.getOrDefault(seasonNumber, 0L), scope.validUntil())));
            });
            projectionRepository.saveAll(projections);

            if (state == null) {
                state = UserSeriesHistoryState.builder()
                        .id(key)
                        .historyRevision(expectedHistoryRevision)
                        .computedRevision(expectedHistoryRevision)
                        .scheduleVersion(loaded.seriesVersion)
                        .validUntil(loaded.validUntil())
                        .build();
            } else {
                state.markComputed(expectedHistoryRevision, loaded.seriesVersion, loaded.validUntil());
            }
            historyStateRepository.saveAndFlush(state);
            return true;
        });
    }

    private DiaryCompletionProjection projection(
            UUID ownerId,
            Content content,
            CompletedPass pass,
            long historyRevision,
            long scheduleVersion,
            Instant validUntil) {
        return DiaryCompletionProjection.builder()
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(content.getType())
                .watchNumber(pass.watchNumber())
                .anchorDiaryEntryId(pass.anchorDiaryEntryId())
                .completedOn(pass.completedOn())
                .orderedAt(pass.orderedAt())
                .historyRevision(historyRevision)
                .scheduleVersion(scheduleVersion)
                .validUntil(validUntil)
                .build();
    }

    private EpisodeWatch toEpisodeWatch(DiaryEntryRepository.EpisodeCompletionWatch row) {
        return new EpisodeWatch(
                row.getDiaryEntryId(),
                new EpisodeCoordinate(row.getSeasonNumber(), row.getEpisodeNumber()),
                row.getWatchNumber(),
                row.getWatchedDate(),
                row.getRecordedAt(),
                row.getWatchedAt());
    }

    private long scopeVersion(CalendarScheduleCompleteness marker) {
        return marker == null || marker.getScopeVersion() == null ? 0L : marker.getScopeVersion();
    }

    private record LoadedScopes(
            VersionedCompletionScope series,
            Map<Integer, VersionedCompletionScope> seasons,
            List<EpisodeWatch> watches,
            Map<Integer, List<EpisodeWatch>> watchesBySeason,
            long seriesVersion,
            Map<Integer, Long> seasonVersions,
            String region,
            String language) {

        private Instant validUntil() {
            return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(series.validUntil()),
                            seasons.values().stream().map(VersionedCompletionScope::validUntil))
                    .min(Comparator.naturalOrder())
                    .orElse(series.validUntil());
        }
    }
}
