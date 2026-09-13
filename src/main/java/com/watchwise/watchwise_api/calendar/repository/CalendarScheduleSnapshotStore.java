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
import org.springframework.dao.DataIntegrityViolationException;

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
@RequiredArgsConstructor
public class CalendarScheduleSnapshotStore {

    private final CalendarScheduleSnapshotRepository snapshotRepository;
    private final CalendarScheduleCompletenessRepository completenessRepository;
    private final NewTransactionExecutor newTransactionExecutor;

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
                : snapshotRepository.findPresentForInterest(region, language, nonEmptyIds(activeMovieIds), nonEmptyIds(activeSeriesIds));
        Set<CalendarAssemblyInput.CompleteSeasonKey> completeSeasonKeys = new LinkedHashSet<>();
        Set<CalendarScheduleKey> completeSeriesKeys = new LinkedHashSet<>();
        (activeSeriesIds.isEmpty() ? List.<CalendarScheduleCompleteness>of()
                : completenessRepository.findCompleteForInterest(region, language, activeSeriesIds)).stream()
                .forEach(marker -> addCompleteKey(marker, language, region, completeSeasonKeys, completeSeriesKeys));
        return new CalendarScheduleReadModel(
                snapshots,
                new CalendarAssemblyInput.Completeness(completeSeasonKeys, completeSeriesKeys));
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

    public boolean upsertMovie(CalendarMovieSchedule schedule) {
        Optional<CalendarScheduleSnapshot> existing = snapshotRepository.findByMovieIdentity(
                schedule.tmdbId(), schedule.region(), schedule.language());
        if (existing.isPresent()) {
            return newTransactionExecutor.runInNewTransaction(() -> {
                CalendarScheduleSnapshot merged = mergeMovie(existing.get(), schedule);
                snapshotRepository.saveAndFlush(merged);
                return movieFactsChanged(existing.get(), merged);
            });
        }
        try {
            return newTransactionExecutor.runInNewTransaction(() -> {
                snapshotRepository.saveAndFlush(newMovie(schedule));
                return true;
            });
        } catch (DataIntegrityViolationException exception) {
            CalendarScheduleSnapshot winner = snapshotRepository.findByMovieIdentity(
                    schedule.tmdbId(), schedule.region(), schedule.language()).orElseThrow(() -> exception);
            return newTransactionExecutor.runInNewTransaction(() -> {
                CalendarScheduleSnapshot merged = mergeMovie(winner, schedule);
                snapshotRepository.saveAndFlush(merged);
                return movieFactsChanged(winner, merged);
            });
        }
    }

    public boolean reconcileSeason(CalendarSeasonSchedule schedule) {
        try {
            return reconcileSeasonInNewTransaction(schedule);
        } catch (DataIntegrityViolationException firstConflict) {
            return reconcileSeasonInNewTransaction(schedule);
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

    private boolean reconcileSeasonInNewTransaction(CalendarSeasonSchedule schedule) {
        return newTransactionExecutor.runInNewTransaction(() -> reconcileSeasonInTransaction(schedule));
    }

    private boolean reconcileSeasonInTransaction(CalendarSeasonSchedule schedule) {
        boolean changed = false;
        boolean hasUsableDate = schedule.episodes().stream().anyMatch(episode -> episode.releaseDate() != null);
        if (hasUsableDate) {
            Set<Integer> coordinates = Set.copyOf(schedule.episodeCoordinates());
            List<CalendarScheduleSnapshot> removed = snapshotsForSeason(schedule).stream()
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
                CalendarScheduleSnapshot merged = mergeEpisode(existing.get(), schedule, episode);
                snapshotRepository.saveAndFlush(merged);
                changed |= episodeFactsChanged(existing.get(), merged);
                continue;
            }
            snapshotRepository.saveAndFlush(newEpisode(schedule, episode));
            changed = true;
        }
        return changed;
    }

    private boolean reconcileSeriesInNewTransaction(CalendarSeriesSchedule schedule, Instant checkedAt) {
        return newTransactionExecutor.runInNewTransaction(() -> reconcileSeriesInTransaction(schedule, checkedAt));
    }

    private boolean reconcileSeriesInTransaction(CalendarSeriesSchedule schedule, Instant checkedAt) {
        Map<Integer, CalendarSeriesSchedule.Season> seasonsByNumber = new LinkedHashMap<>();
        schedule.seasons().forEach(season -> seasonsByNumber.putIfAbsent(season.schedule().seasonNumber(), season));
        boolean changed = false;
        boolean allSeasonsComplete = true;
        for (Map.Entry<Integer, Integer> expected : schedule.expectedEpisodeCountsBySeason().entrySet()) {
            CalendarSeriesSchedule.Season season = seasonsByNumber.get(expected.getKey());
            boolean complete = season != null
                    && season.expectedEpisodeCount() == expected.getValue()
                    && season.isFullyRepresented();
            if (season != null && season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.REMOTE && complete) {
                changed |= reconcileSeasonInTransaction(season.schedule());
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
        boolean hasCachedSeason = schedule.seasons().stream()
                .anyMatch(season -> season.origin() == com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin.CACHE);
        if (!hasCachedSeason) {
            changed |= upsertCompleteness(CalendarScheduleCompleteness.GroupType.SERIES, schedule.key().tmdbId(), null,
                    schedule.key().preferredRegion(), schedule.key().preferredLanguage(),
                    schedule.totalRegularEpisodeCount(), allSeasonsComplete, checkedAt);
        }
        return changed;
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
        CalendarScheduleCompleteness marker = existing.map(current -> current.toBuilder()
                .expectedEpisodeCount(expectedEpisodeCount)
                .complete(complete)
                .lastCheckedAt(toLocalDateTime(checkedAt))
                .build()).orElseGet(() -> CalendarScheduleCompleteness.builder()
                .groupType(groupType)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .region(region)
                .language(language)
                .expectedEpisodeCount(expectedEpisodeCount)
                .complete(complete)
                .lastCheckedAt(toLocalDateTime(checkedAt))
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

    private boolean movieFactsChanged(CalendarScheduleSnapshot existing, CalendarScheduleSnapshot merged) {
        return !Objects.equals(existing.getReleaseDate(), merged.getReleaseDate())
                || !Objects.equals(existing.getTitle(), merged.getTitle())
                || !Objects.equals(existing.getPosterPath(), merged.getPosterPath())
                || !Objects.equals(existing.getPresentInLastTmdbSnapshot(), merged.getPresentInLastTmdbSnapshot());
    }

    private boolean episodeFactsChanged(CalendarScheduleSnapshot existing, CalendarScheduleSnapshot merged) {
        return !Objects.equals(existing.getReleaseDate(), merged.getReleaseDate())
                || !Objects.equals(existing.getTitle(), merged.getTitle())
                || !Objects.equals(existing.getSeriesTitle(), merged.getSeriesTitle())
                || !Objects.equals(existing.getPosterPath(), merged.getPosterPath())
                || !Objects.equals(existing.getStillPath(), merged.getStillPath())
                || !Objects.equals(existing.getPresentInLastTmdbSnapshot(), merged.getPresentInLastTmdbSnapshot());
    }

    private LocalDateTime nextCheckAt(LocalDate releaseDate, Instant checkedAt) {
        return toLocalDateTime(CalendarScheduleCadence.nextCheckAt(releaseDate, checkedAt));
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        if (Instant.MAX.equals(instant)) {
            return LocalDateTime.MAX;
        }
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime();
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
