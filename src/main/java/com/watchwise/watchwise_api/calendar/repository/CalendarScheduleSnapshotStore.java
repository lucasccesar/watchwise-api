package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleCompleteness;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleReadModel;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.ContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CalendarScheduleSnapshotStore {

    private final CalendarScheduleSnapshotRepository snapshotRepository;
    private final CalendarScheduleCompletenessRepository completenessRepository;
    private final NewTransactionExecutor newTransactionExecutor;
    private final CalendarScheduleIdentityLock scheduleLock;

    public CalendarScheduleSnapshotStore(
            CalendarScheduleSnapshotRepository snapshotRepository,
            CalendarScheduleCompletenessRepository completenessRepository,
            NewTransactionExecutor newTransactionExecutor) {
        this(snapshotRepository, completenessRepository, newTransactionExecutor, identity -> { });
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CalendarScheduleReadModel findForInterest(
            CalendarInterest interest, String region, String language) {
        Set<CalendarScheduleKey> activeKeys = interest.sourcesByKey().keySet();
        Set<String> activeMovieIds = activeKeys.stream()
                .filter(key -> key.type() == ContentType.MOVIE)
                .map(CalendarScheduleKey::tmdbId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> activeSeriesIds = activeKeys.stream()
                .filter(key -> key.type() == ContentType.SERIES)
                .map(CalendarScheduleKey::tmdbId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<CalendarScheduleSnapshot> snapshots = activeMovieIds.isEmpty() && activeSeriesIds.isEmpty()
                ? List.of()
                : new java.util.ArrayList<>(snapshotRepository.findPresentForInterest(
                        region, language, nonEmptyIds(activeMovieIds), nonEmptyIds(activeSeriesIds)));
        if (!activeMovieIds.isEmpty()) {
            snapshots.addAll(snapshotRepository.findNegativeMoviesForInterest(
                    region, language, nonEmptyIds(activeMovieIds)));
        }
        Set<CalendarAssemblyInput.CompleteSeasonKey> completeSeasonKeys = new LinkedHashSet<>();
        Set<CalendarScheduleKey> completeSeriesKeys = new LinkedHashSet<>();
        Set<CalendarScheduleKey> negativeSeriesKeys = new LinkedHashSet<>();
        Map<CalendarScheduleKey, LocalDateTime> seriesDiscoveryCheckedAt = new LinkedHashMap<>();
        List<CalendarScheduleCompleteness> seriesMarkers = activeSeriesIds.isEmpty()
                ? List.of()
                : completenessRepository.findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                        CalendarScheduleCompleteness.GroupType.SERIES,
                        activeSeriesIds,
                        region,
                        language);
        seriesMarkers.stream()
                .filter(marker -> !Boolean.TRUE.equals(marker.getComplete())
                        && Integer.valueOf(0).equals(marker.getExpectedEpisodeCount()))
                .map(marker -> new CalendarScheduleKey(ContentType.SERIES, marker.getSeriesTmdbId(), language, region))
                .forEach(negativeSeriesKeys::add);
        seriesMarkers.forEach(marker -> seriesDiscoveryCheckedAt.put(
                new CalendarScheduleKey(ContentType.SERIES, marker.getSeriesTmdbId(), language, region),
                marker.getLastDiscoveredAt() == null ? LocalDateTime.MIN : marker.getLastDiscoveredAt()));
        (activeSeriesIds.isEmpty() ? List.<CalendarScheduleCompleteness>of()
                : completenessRepository.findCompleteForInterest(region, language, activeSeriesIds)).stream()
                .forEach(marker -> addCompleteKey(marker, language, region, completeSeasonKeys, completeSeriesKeys));
        return new CalendarScheduleReadModel(
                snapshots,
                new CalendarAssemblyInput.Completeness(completeSeasonKeys, completeSeriesKeys),
                negativeSeriesKeys,
                seriesDiscoveryCheckedAt);
    }

    public List<CalendarScheduleSnapshot> findDue(
            Collection<CalendarScheduleKey> activeKeys, LocalDateTime now) {
        Map<LocaleKey, ActiveLocaleIds> keysByLocale = new LinkedHashMap<>();
        for (CalendarScheduleKey key : activeKeys) {
            keysByLocale.computeIfAbsent(
                    new LocaleKey(key.preferredRegion(), key.preferredLanguage()), ignored -> new ActiveLocaleIds())
                    .add(key);
        }
        return keysByLocale.entrySet().stream()
                .flatMap(entry -> {
                    LocaleKey locale = entry.getKey();
                    ActiveLocaleIds ids = entry.getValue();
                    return snapshotRepository.findDueForInterest(
                            now,
                            locale.region(),
                            locale.language(),
                            nonEmptyIds(ids.movieTmdbIds()),
                            nonEmptyIds(ids.seriesTmdbIds())).stream();
                })
                .sorted(Comparator.comparing(CalendarScheduleSnapshot::getNextCheckAt))
                .toList();
    }

    public Set<CalendarScheduleKey> findSeriesDueForDiscovery(
            Collection<CalendarScheduleKey> activeKeys, Instant now) {
        Map<LocaleKey, Set<CalendarScheduleKey>> seriesByLocale = new LinkedHashMap<>();
        activeKeys.stream()
                .filter(key -> key.type() == ContentType.SERIES)
                .forEach(key -> seriesByLocale
                        .computeIfAbsent(new LocaleKey(key.preferredRegion(), key.preferredLanguage()), ignored -> new LinkedHashSet<>())
                        .add(key));
        Set<CalendarScheduleKey> due = new LinkedHashSet<>();
        seriesByLocale.forEach((locale, keys) -> {
            Map<String, CalendarScheduleCompleteness> markersBySeries = completenessRepository
                    .findByGroupTypeAndSeriesTmdbIdInAndRegionAndLanguage(
                            CalendarScheduleCompleteness.GroupType.SERIES,
                            keys.stream().map(CalendarScheduleKey::tmdbId).toList(),
                            locale.region(),
                            locale.language())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            CalendarScheduleCompleteness::getSeriesTmdbId,
                            marker -> marker,
                            (first, ignored) -> first));
            keys.stream()
                    .filter(key -> CalendarScheduleCadence.isSeriesDiscoveryDue(
                            Optional.ofNullable(markersBySeries.get(key.tmdbId()))
                                    .map(CalendarScheduleCompleteness::getLastDiscoveredAt)
                                    .orElse(null), now))
                    .forEach(due::add);
        });
        return Set.copyOf(due);
    }

    public boolean upsertMovie(CalendarMovieSchedule schedule) {
        try {
            return newTransactionExecutor.runInNewTransaction(() -> {
                lockMovie(schedule);
                Optional<CalendarScheduleSnapshot> existing = snapshotRepository.findByMovieIdentity(
                        schedule.tmdbId(), schedule.region(), schedule.language());
                if (existing.isPresent()) {
                    if (isOlderThanExisting(existing.get().getLastCheckedAt(), schedule.lastCheckedAt())) {
                        return false;
                    }
                    boolean factsChanged = movieFactsChanged(existing.get(), schedule);
                    CalendarScheduleSnapshot merged = mergeMovie(existing.get(), schedule);
                    snapshotRepository.saveAndFlush(merged);
                    return factsChanged;
                }
                snapshotRepository.saveAndFlush(newMovie(schedule));
                return true;
            });
        } catch (DataIntegrityViolationException exception) {
            return newTransactionExecutor.runInNewTransaction(() -> {
                lockMovie(schedule);
                CalendarScheduleSnapshot winner = snapshotRepository.findByMovieIdentity(
                        schedule.tmdbId(), schedule.region(), schedule.language()).orElseThrow(() -> exception);
                if (isOlderThanExisting(winner.getLastCheckedAt(), schedule.lastCheckedAt())) {
                    return false;
                }
                boolean factsChanged = movieFactsChanged(winner, schedule);
                CalendarScheduleSnapshot merged = mergeMovie(winner, schedule);
                snapshotRepository.saveAndFlush(merged);
                return factsChanged;
            });
        }
    }

    public boolean reconcileSeason(CalendarSeasonSchedule schedule) {
        Instant checkedAt = schedule.episodes().stream()
                .map(CalendarEpisodeSchedule::lastCheckedAt)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(Instant::now);
        return reconcileSeason(schedule, checkedAt);
    }

    public boolean reconcileSeason(CalendarSeasonSchedule schedule, Instant checkedAt) {
        try {
            return reconcileSeasonInNewTransaction(schedule, checkedAt);
        } catch (DataIntegrityViolationException firstConflict) {
            return reconcileSeasonInNewTransaction(schedule, checkedAt);
        }
    }

    public boolean reconcileSeries(CalendarSeriesSchedule schedule, Instant checkedAt) {
        if (!schedule.hasRemoteResults()) {
            return false;
        }
        try {
            return reconcileSeriesInNewTransaction(schedule, checkedAt);
        } catch (DataIntegrityViolationException firstConflict) {
            return reconcileSeriesInNewTransaction(schedule, checkedAt);
        }
    }

    /**
     * Removes a schedule after TMDB has positively reported that the identity no longer exists.
     * A series marker is retained as a negative result so the monthly discovery cadence still applies.
     */
    public void invalidate(CalendarScheduleKey key, Instant checkedAt, boolean discoveryAttempt) {
        newTransactionExecutor.runInNewTransaction(() -> {
            if (key.type() == ContentType.MOVIE) {
                lockMovie(key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
                Optional<CalendarScheduleSnapshot> existing = snapshotRepository.findByMovieIdentity(
                        key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
                if (existing.isPresent() && isOlderThanExisting(existing.get().getLastCheckedAt(), checkedAt)) {
                    return null;
                }
                CalendarScheduleSnapshot tombstone = existing.map(snapshot -> snapshot.toBuilder()
                                .lastCheckedAt(toLocalDateTime(checkedAt))
                                .nextCheckAt(toLocalDateTime(CalendarScheduleCadence.nextNegativeCheckAt(checkedAt)))
                                .presentInLastTmdbSnapshot(false)
                                .build())
                        .orElseGet(() -> CalendarScheduleSnapshot.builder()
                                .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                                .tmdbId(key.tmdbId())
                                .region(key.preferredRegion())
                                .language(key.preferredLanguage())
                                .title(key.tmdbId())
                                .lastCheckedAt(toLocalDateTime(checkedAt))
                                .nextCheckAt(toLocalDateTime(CalendarScheduleCadence.nextNegativeCheckAt(checkedAt)))
                                .presentInLastTmdbSnapshot(false)
                                .build());
                snapshotRepository.saveAndFlush(tombstone);
                return null;
            }

            lockSeries(key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
            List<CalendarScheduleSnapshot> snapshots = snapshotRepository
                    .findByEventTypeAndSeriesTmdbIdAndRegionAndLanguage(
                            CalendarScheduleSnapshot.EventType.EPISODE,
                            key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
            if (snapshots.stream().map(CalendarScheduleSnapshot::getLastCheckedAt)
                    .filter(Objects::nonNull)
                    .anyMatch(lastCheckedAt -> lastCheckedAt.isAfter(toLocalDateTime(checkedAt)))) {
                return null;
            }
            CalendarScheduleCompleteness existingSeriesMarker = completenessRepository.findSeriesIdentity(
                    key.tmdbId(), key.preferredRegion(), key.preferredLanguage()).orElse(null);
            if (existingSeriesMarker != null
                    && existingSeriesMarker.getLastCheckedAt() != null
                    && existingSeriesMarker.getLastCheckedAt().isAfter(toLocalDateTime(checkedAt))) {
                return null;
            }
            snapshotRepository.deleteAll(snapshots);
            completenessRepository.deleteBySeriesTmdbIdAndRegionAndLanguage(
                    key.tmdbId(), key.preferredRegion(), key.preferredLanguage());
            completenessRepository.flush();
            completenessRepository.save(CalendarScheduleCompleteness.builder()
                    .groupType(CalendarScheduleCompleteness.GroupType.SERIES)
                    .seriesTmdbId(key.tmdbId())
                    .region(key.preferredRegion())
                    .language(key.preferredLanguage())
                    .expectedEpisodeCount(0)
                    .complete(false)
                    .lastCheckedAt(toLocalDateTime(checkedAt))
                    .lastDiscoveredAt(discoveryAttempt
                            ? toLocalDateTime(checkedAt)
                            : existingSeriesMarker == null ? null : existingSeriesMarker.getLastDiscoveredAt())
                    .build());
            return null;
        });
    }

    private boolean reconcileSeasonInNewTransaction(CalendarSeasonSchedule schedule, Instant checkedAt) {
        return newTransactionExecutor.runInNewTransaction(() -> {
            lockSeries(schedule.seriesTmdbId(), schedule.region(), schedule.language());
            return reconcileSeasonInTransaction(schedule, true, true, checkedAt);
        });
    }

    private boolean reconcileSeasonInTransaction(
            CalendarSeasonSchedule schedule,
            boolean invalidateCompleteness,
            boolean completePayload,
            Instant checkedAt) {
        List<CalendarScheduleSnapshot> existingSnapshots = snapshotsForSeason(schedule);
        LocalDateTime incomingCheckedAt = toLocalDateTime(checkedAt);
        if (incomingCheckedAt != null && existingSnapshots.stream()
                .map(CalendarScheduleSnapshot::getLastCheckedAt)
                .filter(Objects::nonNull)
                .anyMatch(existingCheckedAt -> existingCheckedAt.isAfter(incomingCheckedAt))) {
            return false;
        }
        if (incomingCheckedAt != null && completenessRepository.findBySeriesTmdbIdAndRegionAndLanguage(
                        schedule.seriesTmdbId(), schedule.region(), schedule.language()).stream()
                .map(CalendarScheduleCompleteness::getLastCheckedAt)
                .filter(Objects::nonNull)
                .anyMatch(existingCheckedAt -> existingCheckedAt.isAfter(incomingCheckedAt))) {
            return false;
        }
        boolean changed = false;
        boolean hasUsableDate = schedule.episodes().stream().anyMatch(episode -> episode.releaseDate() != null);
        if (completePayload || hasUsableDate) {
            Set<Integer> coordinates = Set.copyOf(schedule.episodeCoordinates());
            List<CalendarScheduleSnapshot> removed = existingSnapshots.stream()
                    .filter(snapshot -> !coordinates.contains(snapshot.getEpisodeNumber()))
                    .toList();
            snapshotRepository.deleteAll(removed);
            changed = !removed.isEmpty();
        }

        for (CalendarEpisodeSchedule episode : schedule.episodes()) {
            if (episode.episodeNumber() == null || episode.episodeNumber() <= 0) {
                continue;
            }
            Optional<CalendarScheduleSnapshot> existing = snapshotRepository.findByEpisodeIdentity(
                    schedule.seriesTmdbId(), schedule.seasonNumber(), episode.episodeNumber(),
                    schedule.region(), schedule.language());
            if (existing.isPresent()) {
                boolean factsChanged = episodeFactsChanged(existing.get(), schedule, episode);
                CalendarScheduleSnapshot merged = mergeEpisode(existing.get(), schedule, episode);
                snapshotRepository.saveAndFlush(merged);
                changed |= factsChanged;
                continue;
            }
            snapshotRepository.saveAndFlush(newEpisode(schedule, episode));
            changed = true;
        }
        if (invalidateCompleteness) {
            changed |= upsertCompleteness(CalendarScheduleCompleteness.GroupType.SEASON,
                    schedule.seriesTmdbId(), schedule.seasonNumber(), schedule.region(), schedule.language(),
                    schedule.episodes().size(), true, checkedAt);
            changed |= markSeriesIncomplete(
                    schedule.seriesTmdbId(), schedule.region(), schedule.language(), checkedAt);
        }
        return changed;
    }

    private boolean reconcileSeriesInNewTransaction(CalendarSeriesSchedule schedule, Instant checkedAt) {
        return newTransactionExecutor.runInNewTransaction(() -> {
            lockSeries(schedule.key().tmdbId(), schedule.key().preferredRegion(), schedule.key().preferredLanguage());
            return reconcileSeriesInTransaction(schedule, checkedAt);
        });
    }

    private boolean reconcileSeriesInTransaction(CalendarSeriesSchedule schedule, Instant checkedAt) {
        if (hasNewerSeriesData(schedule, checkedAt)) {
            return false;
        }
        Map<Integer, CalendarSeriesSchedule.Season> seasonsByNumber = new LinkedHashMap<>();
        schedule.seasons().forEach(season -> seasonsByNumber.putIfAbsent(season.schedule().seasonNumber(), season));
        boolean changed = false;
        boolean hasCachedSeason = schedule.seasons().stream()
                .anyMatch(season -> season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.CACHE);
        if (schedule.completeSchedule()) {
            Set<Integer> expectedSeasonNumbers = schedule.expectedEpisodeCountsBySeason().keySet();
            List<CalendarScheduleSnapshot> removedSeasons = snapshotRepository
                    .findByEventTypeAndSeriesTmdbIdAndRegionAndLanguage(
                            CalendarScheduleSnapshot.EventType.EPISODE,
                            schedule.key().tmdbId(),
                            schedule.key().preferredRegion(),
                            schedule.key().preferredLanguage())
                    .stream()
                    .filter(snapshot -> !expectedSeasonNumbers.contains(snapshot.getSeasonNumber()))
                    .toList();
            snapshotRepository.deleteAll(removedSeasons);
            changed |= !removedSeasons.isEmpty();
        }
        boolean hasNewerCompleteness = completenessRepository.findBySeriesTmdbIdAndRegionAndLanguage(
                        schedule.key().tmdbId(), schedule.key().preferredRegion(), schedule.key().preferredLanguage()).stream()
                .map(CalendarScheduleCompleteness::getLastCheckedAt)
                .filter(Objects::nonNull)
                .anyMatch(lastCheckedAt -> lastCheckedAt.isAfter(toLocalDateTime(checkedAt)));
        if (!schedule.completeSchedule() && !hasNewerCompleteness) {
            changed |= completenessRepository.deleteByGroupTypeAndSeriesTmdbIdAndRegionAndLanguage(
                    CalendarScheduleCompleteness.GroupType.SEASON,
                    schedule.key().tmdbId(),
                    schedule.key().preferredRegion(),
                    schedule.key().preferredLanguage()) > 0;
            changed |= markSeriesIncomplete(schedule, checkedAt);
        }
        if (schedule.completeSchedule() && !hasNewerCompleteness) {
            changed |= completenessRepository.deleteBySeriesTmdbIdAndRegionAndLanguage(
                    schedule.key().tmdbId(), schedule.key().preferredRegion(), schedule.key().preferredLanguage()) > 0;
        }
        boolean allSeasonsComplete = true;
        for (Map.Entry<Integer, Integer> expected : schedule.expectedEpisodeCountsBySeason().entrySet()) {
            CalendarSeriesSchedule.Season season = seasonsByNumber.get(expected.getKey());
            boolean complete = season != null
                    && season.expectedEpisodeCount() == expected.getValue()
                    && season.isFullyRepresented();
            if (season != null && season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.REMOTE && complete) {
                changed |= reconcileSeasonInTransaction(season.schedule(), false, complete, checkedAt);
            }
            if (season == null || season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.REMOTE) {
                changed |= upsertCompleteness(CalendarScheduleCompleteness.GroupType.SEASON, schedule.key().tmdbId(),
                        expected.getKey(), schedule.key().preferredRegion(), schedule.key().preferredLanguage(),
                        expected.getValue(), complete, checkedAt);
            }
            allSeasonsComplete &= season != null
                    && season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.REMOTE
                    && complete;
        }
        if (schedule.completeSchedule()) {
            changed |= upsertCompleteness(CalendarScheduleCompleteness.GroupType.SERIES, schedule.key().tmdbId(), null,
                    schedule.key().preferredRegion(), schedule.key().preferredLanguage(),
                    schedule.totalRegularEpisodeCount(), !hasCachedSeason && allSeasonsComplete, checkedAt);
        }
        return changed;
    }

    private boolean markSeriesIncomplete(CalendarSeriesSchedule schedule, Instant checkedAt) {
        return markSeriesIncomplete(schedule.key().tmdbId(), schedule.key().preferredRegion(),
                schedule.key().preferredLanguage(), checkedAt, schedule.totalRegularEpisodeCount());
    }

    private boolean markSeriesIncomplete(String seriesTmdbId, String region, String language, Instant checkedAt) {
        return markSeriesIncomplete(seriesTmdbId, region, language, checkedAt, 0);
    }

    private boolean markSeriesIncomplete(
            String seriesTmdbId, String region, String language, Instant checkedAt, int expectedEpisodeCount) {
        Optional<CalendarScheduleCompleteness> existing = completenessRepository.findSeriesIdentity(
                seriesTmdbId, region, language);
        if (existing.isEmpty()
                || isOlderThanExisting(existing.get().getLastCheckedAt(), checkedAt)) {
            return false;
        }
        CalendarScheduleCompleteness marker = existing.get().toBuilder()
                .expectedEpisodeCount(expectedEpisodeCount > 0
                        ? expectedEpisodeCount : existing.get().getExpectedEpisodeCount())
                .complete(false)
                .lastCheckedAt(toLocalDateTime(checkedAt))
                .build();
        boolean changed = Boolean.TRUE.equals(existing.get().getComplete());
        completenessRepository.save(marker);
        return changed;
    }

    private boolean hasNewerSeriesData(CalendarSeriesSchedule schedule, Instant checkedAt) {
        LocalDateTime incomingCheckedAt = toLocalDateTime(checkedAt);
        boolean newerSnapshot = snapshotRepository.findByEventTypeAndSeriesTmdbIdAndRegionAndLanguage(
                        CalendarScheduleSnapshot.EventType.EPISODE,
                        schedule.key().tmdbId(),
                        schedule.key().preferredRegion(),
                        schedule.key().preferredLanguage()).stream()
                .map(CalendarScheduleSnapshot::getLastCheckedAt)
                .filter(Objects::nonNull)
                .anyMatch(lastCheckedAt -> lastCheckedAt.isAfter(incomingCheckedAt));
        return newerSnapshot || completenessRepository.findBySeriesTmdbIdAndRegionAndLanguage(
                        schedule.key().tmdbId(),
                        schedule.key().preferredRegion(),
                        schedule.key().preferredLanguage()).stream()
                .map(CalendarScheduleCompleteness::getLastCheckedAt)
                .filter(Objects::nonNull)
                .anyMatch(lastCheckedAt -> lastCheckedAt.isAfter(incomingCheckedAt));
    }

    private Collection<String> nonEmptyIds(Collection<String> ids) {
        return ids.isEmpty() ? List.of("__calendar_no_active_identity__") : ids;
    }

    private boolean upsertCompleteness(
            CalendarScheduleCompleteness.GroupType groupType,
            String seriesTmdbId,
            Integer seasonNumber,
            String region,
            String language,
            int expectedEpisodeCount,
            boolean complete,
            Instant checkedAt) {
        Optional<CalendarScheduleCompleteness> existing = groupType == CalendarScheduleCompleteness.GroupType.SEASON
                ? completenessRepository.findSeasonIdentity(seriesTmdbId, seasonNumber, region, language)
                : completenessRepository.findSeriesIdentity(seriesTmdbId, region, language);
        if (existing.isPresent()
                && existing.get().getLastCheckedAt() != null
                && existing.get().getLastCheckedAt().isAfter(toLocalDateTime(checkedAt))) {
            return false;
        }
        CalendarScheduleCompleteness marker = existing.map(current -> current.toBuilder()
                .expectedEpisodeCount(expectedEpisodeCount)
                .complete(complete)
                .lastCheckedAt(toLocalDateTime(checkedAt))
                .lastDiscoveredAt(groupType == CalendarScheduleCompleteness.GroupType.SERIES
                        ? toLocalDateTime(checkedAt) : current.getLastDiscoveredAt())
                .build()).orElseGet(() -> CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .region(region)
                .language(language)
                .expectedEpisodeCount(expectedEpisodeCount)
                .complete(complete)
                .lastCheckedAt(toLocalDateTime(checkedAt))
                .lastDiscoveredAt(groupType == CalendarScheduleCompleteness.GroupType.SERIES
                        ? toLocalDateTime(checkedAt) : null)
                .build());
        boolean changed = existing.isEmpty()
                || !Objects.equals(existing.get().getExpectedEpisodeCount(), marker.getExpectedEpisodeCount())
                || !Objects.equals(existing.get().getComplete(), marker.getComplete());
        completenessRepository.save(marker);
        return changed;
    }

    private void addCompleteKey(
            CalendarScheduleCompleteness marker,
            String language,
            String region,
            Set<CalendarAssemblyInput.CompleteSeasonKey> completeSeasonKeys,
            Set<CalendarScheduleKey> completeSeriesKeys) {
        if (marker.getGroupType() == CalendarScheduleCompleteness.GroupType.SEASON) {
            completeSeasonKeys.add(new CalendarAssemblyInput.CompleteSeasonKey(
                    marker.getSeriesTmdbId(), marker.getSeasonNumber()));
            return;
        }
        completeSeriesKeys.add(new CalendarScheduleKey(
                ContentType.SERIES, marker.getSeriesTmdbId(), language, region));
    }

    private List<CalendarScheduleSnapshot> snapshotsForSeason(CalendarSeasonSchedule schedule) {
        return snapshotRepository.findByEventTypeAndSeriesTmdbIdAndSeasonNumberAndRegionAndLanguage(
                CalendarScheduleSnapshot.EventType.EPISODE,
                schedule.seriesTmdbId(),
                schedule.seasonNumber(),
                schedule.region(),
                schedule.language());
    }

    private CalendarScheduleSnapshot mergeMovie(CalendarScheduleSnapshot existing, CalendarMovieSchedule schedule) {
        LocalDate releaseDate = schedule.releaseDate() != null ? schedule.releaseDate() : existing.getReleaseDate();
        return existing.toBuilder()
                .releaseDate(releaseDate)
                .title(schedule.title())
                .posterPath(schedule.posterPath())
                .lastCheckedAt(toLocalDateTime(schedule.lastCheckedAt()))
                .nextCheckAt(nextCheckAt(releaseDate, schedule.lastCheckedAt()))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private CalendarScheduleSnapshot newMovie(CalendarMovieSchedule schedule) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                .tmdbId(schedule.tmdbId())
                .region(schedule.region())
                .language(schedule.language())
                .releaseDate(schedule.releaseDate())
                .title(schedule.title())
                .posterPath(schedule.posterPath())
                .lastCheckedAt(toLocalDateTime(schedule.lastCheckedAt()))
                .nextCheckAt(nextCheckAt(schedule.releaseDate(), schedule.lastCheckedAt()))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private CalendarScheduleSnapshot mergeEpisode(
            CalendarScheduleSnapshot existing, CalendarSeasonSchedule season, CalendarEpisodeSchedule episode) {
        LocalDate releaseDate = episode.releaseDate() != null ? episode.releaseDate() : existing.getReleaseDate();
        return existing.toBuilder()
                .releaseDate(releaseDate)
                .title(episode.title())
                .seriesTitle(season.seriesTitle())
                .posterPath(season.posterPath())
                .stillPath(episode.stillPath())
                .lastCheckedAt(toLocalDateTime(episode.lastCheckedAt()))
                .nextCheckAt(nextCheckAt(releaseDate, episode.lastCheckedAt()))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private CalendarScheduleSnapshot newEpisode(CalendarSeasonSchedule season, CalendarEpisodeSchedule episode) {
        return CalendarScheduleSnapshot.builder()
                .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                .seriesTmdbId(season.seriesTmdbId())
                .seasonNumber(season.seasonNumber())
                .episodeNumber(episode.episodeNumber())
                .region(season.region())
                .language(season.language())
                .releaseDate(episode.releaseDate())
                .title(episode.title())
                .seriesTitle(season.seriesTitle())
                .posterPath(season.posterPath())
                .stillPath(episode.stillPath())
                .lastCheckedAt(toLocalDateTime(episode.lastCheckedAt()))
                .nextCheckAt(nextCheckAt(episode.releaseDate(), episode.lastCheckedAt()))
                .presentInLastTmdbSnapshot(true)
                .build();
    }

    private boolean movieFactsChanged(CalendarScheduleSnapshot existing, CalendarMovieSchedule schedule) {
        return !Objects.equals(existing.getReleaseDate(), schedule.releaseDate())
                || !Objects.equals(existing.getTitle(), schedule.title())
                || !Objects.equals(existing.getPosterPath(), schedule.posterPath())
                || !Boolean.TRUE.equals(existing.getPresentInLastTmdbSnapshot());
    }

    private boolean episodeFactsChanged(
            CalendarScheduleSnapshot existing, CalendarSeasonSchedule season, CalendarEpisodeSchedule episode) {
        return !Objects.equals(existing.getReleaseDate(), episode.releaseDate())
                || !Objects.equals(existing.getTitle(), episode.title())
                || !Objects.equals(existing.getSeriesTitle(), season.seriesTitle())
                || !Objects.equals(existing.getPosterPath(), season.posterPath())
                || !Objects.equals(existing.getStillPath(), episode.stillPath())
                || !Boolean.TRUE.equals(existing.getPresentInLastTmdbSnapshot());
    }

    private LocalDateTime nextCheckAt(LocalDate releaseDate, Instant checkedAt) {
        return toLocalDateTime(CalendarScheduleCadence.nextCheckAt(releaseDate, checkedAt));
    }

    private void lockMovie(CalendarMovieSchedule schedule) {
        lockMovie(schedule.tmdbId(), schedule.region(), schedule.language());
    }

    private void lockMovie(String tmdbId, String region, String language) {
        scheduleLock.lock("movie|" + tmdbId + "|" + region + "|" + language);
    }

    private void lockSeries(String tmdbId, String region, String language) {
        scheduleLock.lock("series|" + tmdbId + "|" + region + "|" + language);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (Instant.MAX.equals(instant)) {
            return LocalDateTime.MAX;
        }
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }

    private boolean isOlderThanExisting(LocalDateTime existingCheckedAt, Instant incomingCheckedAt) {
        return existingCheckedAt != null && incomingCheckedAt != null
                && existingCheckedAt.isAfter(toLocalDateTime(incomingCheckedAt));
    }

    private record LocaleKey(String region, String language) {
    }

    private static final class ActiveLocaleIds {

        private final Set<String> movieTmdbIds = new LinkedHashSet<>();
        private final Set<String> seriesTmdbIds = new LinkedHashSet<>();

        private void add(CalendarScheduleKey key) {
            if (key.type() == ContentType.MOVIE) {
                movieTmdbIds.add(key.tmdbId());
            } else {
                seriesTmdbIds.add(key.tmdbId());
            }
        }

        private Set<String> movieTmdbIds() {
            return movieTmdbIds;
        }

        private Set<String> seriesTmdbIds() {
            return seriesTmdbIds;
        }
    }
}
