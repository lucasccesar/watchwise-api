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
        boolean synchronizedRemoteSchedule = refreshSchedules(
                activeKeys, readModel.snapshots(), region, language, requestLookups, omittedKeys);

        if (synchronizedRemoteSchedule) {
            readModel = snapshotStore.findForInterest(interest, region, language);
        }
        List<CalendarScheduleSnapshot> snapshots = readModel.snapshots().stream()
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
        return new CalendarResponseDTO(month, region, eventAssembler.assemble(new CalendarAssemblyInput(
                month,
                clock,
                snapshots,
                sources,
                watchedKeys,
                region,
                language,
                readModel.completeness())));
    }

    private boolean refreshSchedules(
            Set<CalendarScheduleKey> activeKeys,
            List<CalendarScheduleSnapshot> snapshots,
            String region,
            String language,
            Map<CalendarScheduleKey, CalendarScheduleLookup> requestLookups,
            Set<CalendarScheduleKey> omittedKeys) {
        boolean synchronizedRemoteSchedule = false;
        Instant now = clock.instant();
        for (CalendarScheduleKey key : activeKeys) {
            List<CalendarScheduleSnapshot> snapshotsForKey = snapshotsForKey(snapshots, key);
            if (!isMissingOrDue(snapshotsForKey, now)) {
                continue;
            }
            CalendarScheduleLookup lookup = requestLookups.computeIfAbsent(key, ignored -> load(key, region, language));
            if (lookup instanceof CalendarScheduleLookup.NotFound) {
                omittedKeys.add(key);
                continue;
            }
            if (lookup instanceof CalendarScheduleLookup.Unavailable) {
                if (snapshotsForKey.isEmpty()) {
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
            if (lookup instanceof CalendarScheduleLookup.FoundSeries foundSeries
                    && foundSeries.schedule().hasRemoteResults()) {
                scheduleSynchronizer.synchronizeSeries(foundSeries.schedule(), now);
                synchronizedRemoteSchedule = true;
            }
        }
        return synchronizedRemoteSchedule;
    }

    private CalendarScheduleLookup load(CalendarScheduleKey key, String region, String language) {
        return key.type() == ContentType.MOVIE
                ? scheduleProvider.loadMovie(key.tmdbId(), region, language)
                : scheduleProvider.loadSeries(key.tmdbId(), region, language);
    }

    private boolean isMissingOrDue(List<CalendarScheduleSnapshot> snapshots, Instant now) {
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
