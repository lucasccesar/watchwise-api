package com.watchwise.watchwise_api.diarycompletion.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleCompletenessRepository;
import com.watchwise.watchwise_api.calendar.service.impl.CalendarScheduleRefreshService;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiaryCompletionServiceImpl implements DiaryCompletionService {

    private static final Duration SERIES_MARKER_REFRESH_COOLDOWN = Duration.ofHours(1);
    private static final int MAX_SEASON_SCOPE_CANDIDATES_PER_READ = 4;
    private static final int MAX_SEASON_REPAIR_CANDIDATES_PER_READ = 32;

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
    private final AtomicInteger seasonScopeCursor = new AtomicInteger();
    private final Cache<UUID, Integer> seasonRepairOffsets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Autowired
    private CalendarScheduleRefreshService calendarScheduleRefreshService;

    @Override
    public void refreshStaleScopes(UUID ownerId, Instant now) {
        refreshStaleScopesInternal(ownerId, now, false);
    }

    private void refreshStaleScopesInternal(UUID ownerId, Instant now, boolean allowPendingSeries) {
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
            if (isStale(state, historyRevision, seriesScheduleVersion, now)
                    || seasonProjectionSeriesIds.contains(seriesId)) {
                refreshSeriesWithRetry(
                        ownerId, seriesId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now,
                        allowPendingSeries);
            }
            refreshSeriesMarkerIfNeeded(
                    ownerId, seriesId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now,
                    allowPendingSeries);
        }
        scheduleMissingSeasonRepairs(ownerId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
        refreshMissingSeasonCandidates(ownerId, owner, now, false);
    }

    @Override
    public void refreshStaleSeasonScopes(UUID ownerId, Instant now) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        List<String> seriesIds = diaryEntryRepository.findSeriesTmdbIdsForDiaryView(ownerId).stream()
                .filter(Objects::nonNull).distinct().toList();
        if (seriesIds.isEmpty()) {
            return;
        }
        Map<String, UserSeriesHistoryState> states = historyStateRepository
                .findByUserIdAndSeriesTmdbIdIn(ownerId, seriesIds).stream()
                .collect(Collectors.toMap(state -> state.getId().getSeriesTmdbId(), Function.identity()));
        Map<String, List<CalendarScheduleCompleteness>> seasonMarkers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SEASON,
                        seriesIds, owner.getPreferredRegion(), owner.getPreferredLanguage())
                .stream().collect(Collectors.groupingBy(CalendarScheduleCompleteness::getSeriesTmdbId));
        Map<String, CalendarScheduleCompleteness> seriesMarkers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SERIES,
                        seriesIds, owner.getPreferredRegion(), owner.getPreferredLanguage())
                .stream().collect(Collectors.toMap(CalendarScheduleCompleteness::getSeriesTmdbId,
                        Function.identity(), (first, ignored) -> first));
        Map<String, List<DiaryCompletionProjectionRepository.SeasonProjectionVersion>> projectionsBySeries =
                projectionRepository.findSeasonProjectionVersions(ownerId).stream()
                        .filter(projection -> projection.getSeriesTmdbId() != null)
                        .collect(Collectors.groupingBy(DiaryCompletionProjectionRepository.SeasonProjectionVersion::getSeriesTmdbId));
        List<String> staleSeries = seriesIds.stream().sorted().filter(seriesId -> {
            UserSeriesHistoryState state = states.get(seriesId);
            List<CalendarScheduleCompleteness> markers = seasonMarkers.getOrDefault(seriesId, List.of());
            CalendarScheduleCompleteness seriesMarker = seriesMarkers.get(seriesId);
            long revision = state == null ? 0L : state.getHistoryRevision();
            return isStale(state, revision, scopeVersion(seriesMarker), now)
                    || projectionsBySeries.getOrDefault(seriesId, List.of()).stream()
                            .anyMatch(projection -> isStaleSeasonProjection(projection, markers, state, now));
        }).toList();
        for (String seriesId : selectSeasonRefreshWindow(staleSeries)) {
            UserSeriesHistoryState state = states.get(seriesId);
            List<CalendarScheduleCompleteness> markers = seasonMarkers.getOrDefault(seriesId, List.of());
            CalendarScheduleCompleteness seriesMarker = seriesMarkers.get(seriesId);
            long revision = state == null ? 0L : state.getHistoryRevision();
            List<DiaryEntryRepository.EpisodeCompletionWatch> rows = diaryEntryRepository
                    .findEpisodeCompletionWatches(ownerId, List.of(seriesId));
            LoadedSeasonScopes loaded = loadSeasonScopes(
                    seriesId, rows, owner.getPreferredRegion(), owner.getPreferredLanguage(), now, markers, true);
            Optional<VersionedCompletionScope> localSeries = scopeLoader.loadPersistedSeries(
                    seriesId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
            if (localSeries.isPresent()) {
                publish(ownerId, seriesId, revision, new LoadedScopes(localSeries.get(), loaded.seasons(),
                        loaded.watches(), loaded.watchesBySeason(), scopeVersion(seriesMarker),
                        loaded.seasonVersions(), loaded.region(), loaded.language()), now);
            } else {
                publishSeasonScopes(ownerId, seriesId, revision, loaded, seriesMarker, now, true);
            }
        }
        scheduleMissingSeasonRepairs(ownerId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
        refreshMissingSeasonCandidates(ownerId, owner, now, true);
    }

    @Override
    public void refreshDiaryViewScopes(UUID ownerId, Instant now, boolean includeSeries) {
        if (!includeSeries) {
            refreshStaleSeasonScopes(ownerId, now);
            return;
        }
        try {
            refreshStaleScopesInternal(ownerId, now, true);
        } catch (TmdbUnavailableException unavailable) {
            refreshStaleSeasonScopes(ownerId, now);
        }
    }

    @Override
    @Transactional
    public void invalidateScheduleScopes(UUID ownerId) {
        historyStateRepository.invalidateScheduleValidityForUser(ownerId, Instant.EPOCH);
    }

    private List<String> selectSeasonRefreshWindow(List<String> candidates) {
        return selectSeasonRefreshWindow(candidates, MAX_SEASON_SCOPE_CANDIDATES_PER_READ);
    }

    private List<String> selectSeasonRefreshWindow(List<String> candidates, int limit) {
        if (candidates.size() <= limit) {
            return candidates;
        }
        int start = Math.floorMod(seasonScopeCursor.getAndAdd(limit), candidates.size());
        List<String> selected = new ArrayList<>(limit);
        for (int offset = 0; offset < limit; offset++) {
            selected.add(candidates.get((start + offset) % candidates.size()));
        }
        return selected;
    }

    private boolean scheduleMissingSeasonRepairs(UUID ownerId, String region, String language, Instant now) {
        if (!scopeLoader.supportsPersistedSchedules()) {
            return false;
        }
        java.time.LocalDateTime checkedAt = java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        int offset = Objects.requireNonNullElse(seasonRepairOffsets.getIfPresent(ownerId), 0);
        List<DiaryEntryRepository.SeasonScheduleRepairCandidate> pending = diaryEntryRepository
                .findSeasonScheduleRepairCandidates(ownerId, checkedAt,
                        MAX_SEASON_REPAIR_CANDIDATES_PER_READ, offset);
        if (pending.isEmpty() && offset > 0) {
            offset = 0;
            pending = diaryEntryRepository.findSeasonScheduleRepairCandidates(ownerId, checkedAt,
                    MAX_SEASON_REPAIR_CANDIDATES_PER_READ, offset);
        }
        if (pending.size() < MAX_SEASON_REPAIR_CANDIDATES_PER_READ) {
            seasonRepairOffsets.invalidate(ownerId);
        } else {
            seasonRepairOffsets.put(ownerId, offset + pending.size());
        }
        if (pending.isEmpty()) {
            return false;
        }
        List<String> keys = pending.stream().filter(Objects::nonNull)
                .filter(candidate -> candidate.getSeriesTmdbId() != null && candidate.getSeasonNumber() != null)
                .map(candidate -> candidate.getSeriesTmdbId() + "\n" + candidate.getSeasonNumber())
                .distinct().toList();
        int admitted = 0;
        for (String selected : keys) {
            if (admitted == MAX_SEASON_SCOPE_CANDIDATES_PER_READ) {
                break;
            }
            String[] parts = selected.split("\\n", 2);
            int seasonNumber;
            try {
                seasonNumber = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (calendarScheduleRefreshService != null && calendarScheduleRefreshService.refreshSeasonInBackground(
                    parts[0], seasonNumber, region, language)) {
                admitted++;
            }
        }
        return admitted > 0;
    }

    private boolean isStaleSeasonProjection(
            DiaryCompletionProjectionRepository.SeasonProjectionVersion projection,
            List<CalendarScheduleCompleteness> seasonMarkers,
            UserSeriesHistoryState state,
            Instant now) {
        CalendarScheduleCompleteness marker = seasonMarkers.stream()
                .filter(candidate -> Objects.equals(candidate.getSeasonNumber(), projection.getSeasonNumber()))
                .findFirst().orElse(null);
        return (marker == null
                ? !Objects.equals(projection.getScheduleVersion(), 0L)
                : !Objects.equals(projection.getScheduleVersion(), scopeVersion(marker)))
                || state != null && !Objects.equals(projection.getHistoryRevision(), state.getHistoryRevision())
                || projection.getValidUntil() == null || !projection.getValidUntil().isAfter(now);
    }

    private void refreshMissingSeasonCandidates(UUID ownerId, User owner, Instant now, boolean markComputed) {
        List<DiaryEntryRepository.SeasonCompletionCandidate> candidates = diaryEntryRepository
                .findSeasonCompletionCandidates(ownerId,
                        java.time.LocalDate.ofInstant(now, java.time.ZoneId.systemDefault()),
                        java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC), now, 4);
        Map<SeasonCandidateKey, Set<Integer>> watchNumbersBySeason = new LinkedHashMap<>();
        for (DiaryEntryRepository.SeasonCompletionCandidate candidate : candidates) {
            if (candidate == null || candidate.getSeriesTmdbId() == null
                    || candidate.getSeasonNumber() == null || candidate.getSeasonNumber() <= 0
                    || candidate.getWatchNumber() == null || candidate.getWatchNumber() <= 0) {
                continue;
            }
            watchNumbersBySeason.computeIfAbsent(
                    new SeasonCandidateKey(candidate.getSeriesTmdbId(), candidate.getSeasonNumber()),
                    ignored -> new java.util.TreeSet<>()).add(candidate.getWatchNumber());
        }
        Set<String> seriesRefreshed = new HashSet<>();
        for (Map.Entry<SeasonCandidateKey, Set<Integer>> entry : watchNumbersBySeason.entrySet()) {
            SeasonCandidateKey key = entry.getKey();
            UserSeriesHistoryState state = historyStateRepository.findById(
                    new UserSeriesHistoryState.Key(ownerId, key.seriesTmdbId())).orElse(null);
            if (state == null || !Objects.equals(state.getHistoryRevision(), state.getComputedRevision())) {
                continue;
            }
            List<CalendarScheduleCompleteness> seasonMarkers = completenessRepository
                    .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                            CalendarScheduleCompleteness.GroupType.SEASON,
                            List.of(key.seriesTmdbId()), owner.getPreferredRegion(), owner.getPreferredLanguage());
            List<DiaryEntryRepository.EpisodeCompletionWatch> rows = diaryEntryRepository
                    .findEpisodeCompletionWatchesBySeason(ownerId, key.seriesTmdbId(), List.of(key.seasonNumber()));
            LoadedSeasonScopes loaded = loadSeasonScopes(key.seriesTmdbId(), rows,
                    owner.getPreferredRegion(), owner.getPreferredLanguage(), now, seasonMarkers, false)
                    .requiringPasses(List.copyOf(entry.getValue()));
            CalendarScheduleCompleteness seriesMarker = completenessRepository.findSeriesIdentity(
                    key.seriesTmdbId(), owner.getPreferredRegion(), owner.getPreferredLanguage()).orElse(null);
            if (!publishSeasonScopes(ownerId, key.seriesTmdbId(), state.getHistoryRevision(),
                    loaded, seriesMarker, now, markComputed)) {
                continue;
            }
            if (seriesRefreshed.add(key.seriesTmdbId())) {
                publishSeriesFromPersistedSchedulesIfNeeded(
                        ownerId, key.seriesTmdbId(), owner, state, seriesMarker, seasonMarkers, now);
            }
        }
    }

    private void publishSeriesFromPersistedSchedulesIfNeeded(
            UUID ownerId,
            String seriesId,
            User owner,
            UserSeriesHistoryState state,
            CalendarScheduleCompleteness seriesMarker,
            List<CalendarScheduleCompleteness> seasonMarkers,
            Instant now) {
        if (!scopeLoader.supportsPersistedSchedules()
                || !Boolean.TRUE.equals(seriesMarker == null ? null : seriesMarker.getComplete())
                || (projectionRepository.existsCurrentSeriesProjection(ownerId, seriesId)
                        && !isStale(state, state.getHistoryRevision(), scopeVersion(seriesMarker), now))) {
            return;
        }
        List<DiaryEntryRepository.EpisodeCompletionWatch> allWatches = diaryEntryRepository
                .findEpisodeCompletionWatches(ownerId, List.of(seriesId));
        LoadedSeasonScopes allSeasons = loadSeasonScopes(
                seriesId, allWatches, owner.getPreferredRegion(), owner.getPreferredLanguage(), now, seasonMarkers, true);
        if (!allSeasons.seasons().keySet().containsAll(allSeasons.affectedSeasons())) {
            return;
        }
        Optional<VersionedCompletionScope> persistedSeries = scopeLoader.loadPersistedSeries(
                seriesId, owner.getPreferredRegion(), owner.getPreferredLanguage(), now);
        if (persistedSeries.isEmpty() || !persistedSeries.get().scope().releaseComplete()) {
            return;
        }
        LoadedScopes loaded = new LoadedScopes(
                persistedSeries.get(), allSeasons.seasons(), allSeasons.watches(), allSeasons.watchesBySeason(),
                scopeVersion(seriesMarker), allSeasons.seasonVersions(), allSeasons.region(), allSeasons.language());
        publish(ownerId, seriesId, state.getHistoryRevision(), loaded, now);
    }

    private void refreshSeriesMarkerIfNeeded(
            UUID ownerId, String seriesId, String region, String language, Instant now,
            boolean allowPendingSeries) {
        if (allowPendingSeries) {
            return;
        }
        CalendarScheduleCompleteness marker = completenessRepository
                .findSeriesIdentity(seriesId, region, language).orElse(null);
        if (marker != null && Boolean.TRUE.equals(marker.getComplete())) {
            return;
        }
        if (marker != null && marker.getLastCheckedAt() != null
                && marker.getLastCheckedAt().toInstant(ZoneOffset.UTC)
                        .isAfter(now.minus(SERIES_MARKER_REFRESH_COOLDOWN))) {
            return;
        }
        if (calendarScheduleRefreshService == null
                || !projectionRepository.existsCurrentSeriesProjection(ownerId, seriesId)
                || !calendarScheduleRefreshService.refreshSeriesNow(seriesId, region, language)) {
            return;
        }

        UserSeriesHistoryState state = historyStateRepository.findById(
                new UserSeriesHistoryState.Key(ownerId, seriesId)).orElse(null);
        if (state == null) {
            return;
        }
        CalendarScheduleCompleteness refreshedMarker = completenessRepository
                .findSeriesIdentity(seriesId, region, language).orElse(null);
        List<CalendarScheduleCompleteness> seasonMarkers = completenessRepository
                .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SEASON,
                        List.of(seriesId), region, language);
        if (isStale(state, state.getHistoryRevision(),
                scopeVersion(refreshedMarker), now)
                || hasStaleSeasonProjection(ownerId, seriesId, seasonMarkers)) {
            refreshSeriesWithRetry(ownerId, seriesId, region, language, now, false);
        }
    }

    private void refreshSeriesWithRetry(
            UUID ownerId,
            String seriesId,
            String region,
            String language,
            Instant now,
            boolean allowPendingSeries) {
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
            LoadedSeasonScopes seasonScopes = loadSeasonScopes(
                    seriesId, watches, region, language, now, seasonMarkers, true);
            if (!publishSeasonScopes(ownerId, seriesId, historyRevision, seasonScopes, seriesMarker, now, false)) {
                continue;
            }
            LoadedScopes loaded = loadScopes(
                    ownerId, seriesId, seasonScopes, region, language, now, seriesMarker, allowPendingSeries);
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

    private LoadedSeasonScopes loadSeasonScopes(
            String seriesId,
            List<DiaryEntryRepository.EpisodeCompletionWatch> rows,
            String region,
            String language,
            Instant now,
            List<CalendarScheduleCompleteness> seasonMarkers,
            boolean replaceAll) {
        Map<Integer, VersionedCompletionScope> seasons = new LinkedHashMap<>();
        List<Integer> watchedSeasons = rows.stream()
                .map(DiaryEntryRepository.EpisodeCompletionWatch::getSeasonNumber)
                .filter(Objects::nonNull)
                .filter(season -> season > 0)
                .distinct()
                .sorted()
                .toList();
        CompletionScopeLoader.PersistedSeasonScopes persisted = scopeLoader.loadPersistedSeasons(
                seriesId, watchedSeasons, region, language, now);
        if (persisted == null || !persisted.supported()) {
            watchedSeasons.forEach(season -> seasons.put(season,
                    scopeLoader.load(MediaReference.season(seriesId, season), region, language, now)));
        } else {
            seasons.putAll(persisted.scopes());
        }
        Map<Integer, Long> seasonVersions = seasonMarkers.stream()
                .filter(marker -> marker.getSeasonNumber() != null)
                .collect(Collectors.toMap(CalendarScheduleCompleteness::getSeasonNumber, this::scopeVersion));
        seasons.forEach((seasonNumber, scope) -> {
            try {
                seasonVersions.put(seasonNumber, Long.parseLong(scope.scheduleVersion()));
            } catch (NumberFormatException ignored) {
                // Non-persisted test/custom loaders can use opaque versions; keep their marker snapshot.
            }
        });
        List<EpisodeWatch> watches = rows.stream().map(this::toEpisodeWatch).toList();
        Map<Integer, List<EpisodeWatch>> watchesBySeason = watches.stream()
                .collect(Collectors.groupingBy(watch -> watch.episode().seasonNumber()));
        return new LoadedSeasonScopes(
                seasons, watches, watchesBySeason, seasonVersions, seasonMarkers,
                watchedSeasons, replaceAll, List.of(), region, language);
    }

    private LoadedScopes loadScopes(
            UUID ownerId,
            String seriesId,
            LoadedSeasonScopes seasonScopes,
            String region,
            String language,
            Instant now,
            CalendarScheduleCompleteness seriesMarker,
            boolean allowPendingSeries) {
        Optional<VersionedCompletionScope> persistedSeries = scopeLoader.loadPersistedSeries(
                seriesId, region, language, now);
        VersionedCompletionScope series;
        if (scopeLoader.supportsPersistedSchedules()
                && (seriesMarker == null || !Boolean.TRUE.equals(seriesMarker.getComplete()))) {
            if (allowPendingSeries) {
                series = persistedSeries.orElseGet(() -> incompleteLocalSeriesScope(
                        seriesId, seriesMarker, seasonScopes, now));
            } else {
                scheduleMissingSeasonRepairs(ownerId, region, language, now);
                series = scopeLoader.load(MediaReference.series(seriesId), region, language, now);
            }
        } else if (persistedSeries.isPresent()) {
            series = persistedSeries.get();
        } else if (allowPendingSeries && scopeLoader.supportsPersistedSchedules()) {
            series = incompleteLocalSeriesScope(seriesId, seriesMarker, seasonScopes, now);
        } else {
            series = scopeLoader.load(MediaReference.series(seriesId), region, language, now);
        }
        return new LoadedScopes(
                series,
                seasonScopes.seasons(),
                seasonScopes.watches(),
                seasonScopes.watchesBySeason(),
                scopeVersion(seriesMarker),
                seasonScopes.seasonVersions(),
                region,
                language);
    }

    private VersionedCompletionScope incompleteLocalSeriesScope(
            String seriesId,
            CalendarScheduleCompleteness seriesMarker,
            LoadedSeasonScopes seasonScopes,
            Instant now) {
        Set<EpisodeCoordinate> knownCoordinates = seasonScopes.seasons().values().stream()
                .flatMap(scope -> scope.scope().episodes().stream())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Instant validUntil = seasonScopes.seasons().values().stream()
                .map(VersionedCompletionScope::validUntil)
                .min(Comparator.naturalOrder())
                .orElseGet(() -> CalendarScheduleCadence.nextCheckAt(null, now));
        return new VersionedCompletionScope(
                new CompletionScope(MediaReference.series(seriesId), knownCoordinates, false),
                String.valueOf(scopeVersion(seriesMarker)), validUntil);
    }

    private boolean publishSeasonScopes(
            UUID ownerId, String seriesId, long expectedHistoryRevision, LoadedSeasonScopes loaded,
            CalendarScheduleCompleteness seriesMarker, Instant now, boolean markComputed) {
        if (loaded.seasons().isEmpty() && !loaded.replaceAll()) {
            return true;
        }
        return newTransactionExecutor.runInNewTransaction(() -> {
            userContentMutationLock.lock(ownerId);
            if (!hasMatchingSchedulePreferences(ownerId, loaded.region(), loaded.language())) {
                return false;
            }
            UserSeriesHistoryState.Key key = new UserSeriesHistoryState.Key(ownerId, seriesId);
            UserSeriesHistoryState state = historyStateRepository.findById(key).orElse(null);
            if ((state == null && expectedHistoryRevision != 0L)
                    || (state != null && !Objects.equals(state.getHistoryRevision(), expectedHistoryRevision))) {
                return false;
            }
            Map<Integer, Long> currentSeasonVersions = completenessRepository
                    .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                            CalendarScheduleCompleteness.GroupType.SEASON,
                            List.of(seriesId), loaded.region(), loaded.language())
                    .stream().collect(Collectors.toMap(CalendarScheduleCompleteness::getSeasonNumber,
                            this::scopeVersion));
            if (!loaded.seasonVersions().keySet().equals(currentSeasonVersions.keySet())
                    || loaded.seasonVersions().entrySet().stream().anyMatch(entry ->
                    !Objects.equals(entry.getValue(), currentSeasonVersions.getOrDefault(entry.getKey(), 0L)))) {
                return false;
            }

            List<Content> seasons = contentRepository.findByTypeAndSeriesTmdbIdIn(ContentType.SEASON, List.of(seriesId))
                    .stream()
                    .filter(content -> loaded.replaceAll() || loaded.affectedSeasons().contains(content.getSeasonNumber()))
                    .toList();
            if (!loaded.replaceAll() && !loaded.requiredWatchNumbers().isEmpty()) {
                boolean alreadyCurrent = loaded.requiredWatchNumbers().stream().allMatch(watchNumber ->
                        seasons.stream().filter(content -> Objects.equals(
                                        content.getSeasonNumber(), loaded.affectedSeasons().getFirst()))
                                .findFirst()
                                .flatMap(content -> projectionRepository.findByUserIdAndContentIdAndWatchNumber(
                                        ownerId, content.getId(), watchNumber).stream()
                                        .filter(projection -> Objects.equals(projection.getHistoryRevision(), expectedHistoryRevision)
                                                && Objects.equals(projection.getScheduleVersion(), loaded.seasonVersions()
                                                        .getOrDefault(content.getSeasonNumber(), 0L))
                                                && projection.getValidUntil() != null
                                                && projection.getValidUntil().isAfter(now))
                                        .findFirst())
                                .isPresent());
                if (alreadyCurrent) {
                    return true;
                }
            }
            contentRepository.findByTmdbIdAndType(seriesId, ContentType.SERIES)
                    .ifPresent(series -> projectionRepository.deleteByUserIdAndContentId(ownerId, series.getId()));
            seasons.forEach(content -> projectionRepository.deleteByUserIdAndContentId(ownerId, content.getId()));
            projectionRepository.flush();
            Map<Integer, Content> byNumber = seasons.stream().collect(Collectors.toMap(
                    Content::getSeasonNumber, Function.identity(), (first, ignored) -> first));
            List<DiaryCompletionProjection> projections = new ArrayList<>();
            loaded.seasons().forEach((seasonNumber, scope) -> {
                Content content = byNumber.get(seasonNumber);
                if (content == null) {
                    return;
                }
                completionCalculator.calculate(scope.scope(), loaded.watchesBySeason()
                                .getOrDefault(seasonNumber, List.of()))
                        .forEach(pass -> projections.add(projection(ownerId, content, pass,
                                expectedHistoryRevision, loaded.seasonVersions().getOrDefault(seasonNumber, 0L),
                                scope.validUntil())));
            });
            projectionRepository.saveAll(projections);
            if (markComputed) {
                Instant validUntil = loaded.seasons().values().stream()
                        .map(VersionedCompletionScope::validUntil)
                        .min(Comparator.naturalOrder())
                        .orElse(now.plus(SERIES_MARKER_REFRESH_COOLDOWN));
                if (state == null) {
                    state = UserSeriesHistoryState.builder()
                            .id(key)
                            .historyRevision(expectedHistoryRevision)
                            .computedRevision(expectedHistoryRevision)
                            .scheduleVersion(scopeVersion(seriesMarker))
                            .validUntil(validUntil)
                            .build();
                } else {
                    state.markComputed(expectedHistoryRevision, scopeVersion(seriesMarker), validUntil);
                }
                historyStateRepository.saveAndFlush(state);
            }
            return true;
        });
    }

    private boolean publish(
            UUID ownerId,
            String seriesId,
            long expectedHistoryRevision,
            LoadedScopes loaded,
            Instant now) {
        return newTransactionExecutor.runInNewTransaction(() -> {
            userContentMutationLock.lock(ownerId);
            if (!hasMatchingSchedulePreferences(ownerId, loaded.region(), loaded.language())) {
                return false;
            }
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
            if (!loaded.seasonVersions().keySet().equals(currentSeasonVersions.keySet())
                    || loaded.seasonVersions().entrySet().stream()
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

    private boolean hasMatchingSchedulePreferences(UUID ownerId, String region, String language) {
        User owner = userRepository.findById(ownerId).orElse(null);
        return owner != null
                && Objects.equals(owner.getPreferredRegion(), region)
                && Objects.equals(owner.getPreferredLanguage(), language);
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

    private record LoadedSeasonScopes(
            Map<Integer, VersionedCompletionScope> seasons,
            List<EpisodeWatch> watches,
            Map<Integer, List<EpisodeWatch>> watchesBySeason,
            Map<Integer, Long> seasonVersions,
            List<CalendarScheduleCompleteness> seasonMarkers,
            List<Integer> affectedSeasons,
            boolean replaceAll,
            List<Integer> requiredWatchNumbers,
            String region,
            String language) {

        private LoadedSeasonScopes requiringPasses(List<Integer> watchNumbers) {
            return new LoadedSeasonScopes(seasons, watches, watchesBySeason, seasonVersions, seasonMarkers,
                    affectedSeasons, replaceAll, watchNumbers, region, language);
        }
    }

    private record SeasonCandidateKey(String seriesTmdbId, int seasonNumber) { }
}
