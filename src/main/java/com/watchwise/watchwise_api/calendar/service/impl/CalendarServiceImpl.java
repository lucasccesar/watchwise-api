package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.dto.CalendarResponseDTO;
import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarAssemblyInput;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarInterestReader;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleReadModel;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleSynchronizer;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarService;
import com.watchwise.watchwise_api.calendar.service.CalendarWatchedContentReader;
import com.watchwise.watchwise_api.calendar.service.WatchedCalendarKey;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CalendarServiceImpl implements CalendarService {

    private final CalendarInterestReader interestReader;
    private final CalendarScheduleSnapshotStore snapshotStore;
    private final CalendarScheduleProvider scheduleProvider;
    private final CalendarScheduleSynchronizer scheduleSynchronizer;
    private final CalendarWatchedContentReader watchedContentReader;
    private final Clock clock;
    private final CalendarEventAssembler eventAssembler = new CalendarEventAssembler();

    @Override
    public CalendarResponseDTO getMonth(UUID userId, YearMonth month) {
        CalendarInterest interest = interestReader.read(userId);
        String region = interest.preferredRegion();
        String language = interest.preferredLanguage();
        Set<CalendarScheduleKey> activeKeys = interest.sourcesByKey().keySet();
        if (activeKeys.isEmpty()) {
            return new CalendarResponseDTO(month, region, List.of());
        }

        CalendarScheduleReadModel readModel = snapshotStore.findForInterest(interest, region, language);
        Map<CalendarScheduleKey, CalendarScheduleLookup> requestLookups = new LinkedHashMap<>();
        Set<CalendarScheduleKey> omittedKeys = new LinkedHashSet<>();
        List<CalendarScheduleSnapshot> transientSnapshots = new ArrayList<>();
        boolean synchronizedRemoteSchedule = refreshSchedules(
                activeKeys,
                readModel.snapshots(),
                readModel.completeness(),
                transientSnapshots,
                region,
                language,
                requestLookups,
                omittedKeys,
                readModel.negativeSeriesKeys(),
                readModel.seriesDiscoveryCheckedAt());

        if (synchronizedRemoteSchedule) {
            readModel = snapshotStore.findForInterest(interest, region, language);
        }
        List<CalendarScheduleSnapshot> snapshots = mergeSnapshots(readModel.snapshots(), transientSnapshots).stream()
                .filter(snapshot -> !omittedKeys.contains(snapshotKey(snapshot)))
                .toList();
        Set<WatchedCalendarKey> requestedWatchedKeys = watchedKeysFor(snapshots);
        Set<WatchedCalendarKey> watchedKeys = watchedContentReader.readWatchedKeys(userId, requestedWatchedKeys);
        Map<CalendarScheduleKey, Set<com.watchwise.watchwise_api.calendar.dto.CalendarSource>> sources = new LinkedHashMap<>();
        interest.sourcesByKey().forEach((key, value) -> {
            if (!omittedKeys.contains(key)) {
                sources.put(key, value);
            }
        });
        CalendarAssemblyInput.Completeness completeness = filterUncertainCompleteness(
                readModel.completeness(), requestLookups);
        return new CalendarResponseDTO(month, region, eventAssembler.assemble(new CalendarAssemblyInput(
                month,
                clock,
                snapshots,
                sources,
                watchedKeys,
                region,
                language,
                completeness)));
    }

    private boolean refreshSchedules(
            Set<CalendarScheduleKey> activeKeys,
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput.Completeness completeness,
            List<CalendarScheduleSnapshot> transientSnapshots,
            String region,
            String language,
            Map<CalendarScheduleKey, CalendarScheduleLookup> requestLookups,
            Set<CalendarScheduleKey> omittedKeys,
            Set<CalendarScheduleKey> negativeSeriesKeys,
            Map<CalendarScheduleKey, LocalDateTime> seriesDiscoveryCheckedAt) {
        boolean synchronizedRemoteSchedule = false;
        Instant now = clock.instant();
        for (CalendarScheduleKey key : activeKeys) {
            List<CalendarScheduleSnapshot> snapshotsForKey = snapshotsForKey(snapshots, key);
            if (!isMissingOrDue(key, snapshotsForKey, completeness, negativeSeriesKeys,
                    seriesDiscoveryCheckedAt, now)) {
                continue;
            }
            CalendarScheduleLookup lookup = requestLookups.computeIfAbsent(key, ignored -> load(key, region, language));
            if (lookup instanceof CalendarScheduleLookup.NotFound) {
                snapshotStore.invalidate(key, now, key.type() == ContentType.SERIES);
                omittedKeys.add(key);
                continue;
            }
            if (lookup instanceof CalendarScheduleLookup.Unavailable) {
                boolean hasUsableSnapshot = snapshotsForKey.stream()
                        .anyMatch(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot()));
                if (key.type() == ContentType.SERIES || !hasUsableSnapshot) {
                    throw new TmdbUnavailableException("TMDB is currently unavailable");
                }
                continue;
            }
            if (lookup instanceof CalendarScheduleLookup.Found found
                    && found.batch().origin() == TmdbLookupOrigin.REMOTE) {
                scheduleSynchronizer.synchronize(found.batch(), now);
                synchronizedRemoteSchedule = true;
                continue;
            }
            if (lookup instanceof CalendarScheduleLookup.Found found && snapshotsForKey.isEmpty()) {
                transientSnapshots.addAll(inMemorySnapshots(found.batch()));
                continue;
            }
            if (lookup instanceof CalendarScheduleLookup.FoundSeries foundSeries) {
                foundSeries.schedule().seasons().stream()
                        .filter(season -> season.origin() == TmdbLookupOrigin.CACHE)
                        .forEach(season -> transientSnapshots.addAll(inMemorySnapshots(new CalendarScheduleBatch(
                                foundSeries.schedule().key(), season.origin(), now, null, season.schedule()))));
                if (foundSeries.schedule().hasRemoteResults()) {
                    scheduleSynchronizer.synchronizeSeries(foundSeries.schedule(), now);
                    synchronizedRemoteSchedule = true;
                }
            }
        }
        return synchronizedRemoteSchedule;
    }

    private List<CalendarScheduleSnapshot> mergeSnapshots(
            Collection<CalendarScheduleSnapshot> refreshed,
            Collection<CalendarScheduleSnapshot> requestSnapshots) {
        Map<String, CalendarScheduleSnapshot> snapshotsByIdentity = new LinkedHashMap<>();
        refreshed.forEach(snapshot -> snapshotsByIdentity.put(snapshotIdentity(snapshot), snapshot));
        requestSnapshots.forEach(snapshot -> snapshotsByIdentity.putIfAbsent(snapshotIdentity(snapshot), snapshot));
        return List.copyOf(snapshotsByIdentity.values());
    }

    private CalendarAssemblyInput.Completeness filterUncertainCompleteness(
            CalendarAssemblyInput.Completeness completeness,
            Map<CalendarScheduleKey, CalendarScheduleLookup> requestLookups) {
        Set<String> uncertainSeriesIds = requestLookups.values().stream()
                .filter(CalendarScheduleLookup.FoundSeries.class::isInstance)
                .map(CalendarScheduleLookup.FoundSeries.class::cast)
                .filter(found -> found.schedule().seasons().stream()
                        .anyMatch(season -> season.origin() == TmdbLookupOrigin.CACHE))
                .map(found -> found.schedule().key().tmdbId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (uncertainSeriesIds.isEmpty()) {
            return completeness;
        }
        return new CalendarAssemblyInput.Completeness(
                completeness.completeSeasonKeys().stream()
                        .filter(key -> !uncertainSeriesIds.contains(key.seriesTmdbId()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                completeness.completeSeriesKeys().stream()
                        .filter(key -> !uncertainSeriesIds.contains(key.tmdbId()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private String snapshotIdentity(CalendarScheduleSnapshot snapshot) {
        if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE) {
            return "MOVIE|" + snapshot.getTmdbId() + "|" + snapshot.getRegion() + "|" + snapshot.getLanguage();
        }
        return "EPISODE|" + snapshot.getSeriesTmdbId() + "|" + snapshot.getSeasonNumber() + "|"
                + snapshot.getEpisodeNumber() + "|" + snapshot.getRegion() + "|" + snapshot.getLanguage();
    }

    private List<CalendarScheduleSnapshot> inMemorySnapshots(CalendarScheduleBatch batch) {
        if (batch.movie() != null) {
            var movie = batch.movie();
            return List.of(CalendarScheduleSnapshot.builder()
                    .eventType(CalendarScheduleSnapshot.EventType.MOVIE)
                    .tmdbId(movie.tmdbId()).region(movie.region()).language(movie.language())
                    .releaseDate(movie.releaseDate()).title(movie.title()).posterPath(movie.posterPath())
                    .lastCheckedAt(LocalDateTime.ofInstant(batch.loadedAt(), ZoneOffset.UTC))
                    .nextCheckAt(LocalDateTime.MAX).presentInLastTmdbSnapshot(true).build());
        }
        CalendarSeasonSchedule season = batch.season();
        return season.episodes().stream().filter(episode -> episode.episodeNumber() != null && episode.episodeNumber() > 0)
                .map(episode -> CalendarScheduleSnapshot.builder()
                        .eventType(CalendarScheduleSnapshot.EventType.EPISODE)
                        .seriesTmdbId(season.seriesTmdbId()).seasonNumber(season.seasonNumber())
                        .episodeNumber(episode.episodeNumber()).region(season.region()).language(season.language())
                        .releaseDate(episode.releaseDate()).title(episode.title()).seriesTitle(season.seriesTitle())
                        .posterPath(season.posterPath()).stillPath(episode.stillPath())
                        .lastCheckedAt(LocalDateTime.ofInstant(batch.loadedAt(), ZoneOffset.UTC))
                        .nextCheckAt(LocalDateTime.MAX).presentInLastTmdbSnapshot(true).build())
                .toList();
    }

    private CalendarScheduleLookup load(CalendarScheduleKey key, String region, String language) {
        return key.type() == ContentType.MOVIE
                ? scheduleProvider.loadMovie(key.tmdbId(), region, language)
                : scheduleProvider.loadSeries(key.tmdbId(), region, language);
    }

    private boolean isMissingOrDue(
            CalendarScheduleKey key,
            List<CalendarScheduleSnapshot> snapshots,
            CalendarAssemblyInput.Completeness completeness,
            Set<CalendarScheduleKey> negativeSeriesKeys,
            Map<CalendarScheduleKey, LocalDateTime> seriesDiscoveryCheckedAt,
            Instant now) {
        if (key.type() == ContentType.SERIES) {
            if (seriesDiscoveryCheckedAt.containsKey(key)
                    && CalendarScheduleCadence.isSeriesDiscoveryDue(seriesDiscoveryCheckedAt.get(key), now)) {
                return true;
            }
            if (negativeSeriesKeys.contains(key)) {
                return false;
            }
            if (!completeness.completeSeriesKeys().contains(key)) {
                return true;
            }
            return snapshots.stream().anyMatch(snapshot -> snapshot.getNextCheckAt()
                    .isBefore(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                    || snapshot.getNextCheckAt().isEqual(LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
        }
        return snapshots.isEmpty() || snapshots.stream().anyMatch(snapshot -> snapshot.getNextCheckAt()
                .isBefore(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                || snapshot.getNextCheckAt().isEqual(LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    private List<CalendarScheduleSnapshot> snapshotsForKey(
            Collection<CalendarScheduleSnapshot> snapshots, CalendarScheduleKey key) {
        return snapshots.stream().filter(snapshot -> key.equals(snapshotKey(snapshot))).toList();
    }

    private CalendarScheduleKey snapshotKey(CalendarScheduleSnapshot snapshot) {
        ContentType type = snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE
                ? ContentType.MOVIE : ContentType.SERIES;
        return new CalendarScheduleKey(
                type,
                type == ContentType.MOVIE ? snapshot.getTmdbId() : snapshot.getSeriesTmdbId(),
                snapshot.getLanguage(),
                snapshot.getRegion());
    }

    private Set<WatchedCalendarKey> watchedKeysFor(Collection<CalendarScheduleSnapshot> snapshots) {
        Set<WatchedCalendarKey> keys = new LinkedHashSet<>();
        for (CalendarScheduleSnapshot snapshot : snapshots) {
            if (snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE) {
                keys.add(WatchedCalendarKey.movie(snapshot.getTmdbId()));
            } else {
                keys.add(WatchedCalendarKey.episode(
                        snapshot.getSeriesTmdbId(), snapshot.getSeasonNumber(), snapshot.getEpisodeNumber()));
            }
        }
        return Set.copyOf(keys);
    }
}
