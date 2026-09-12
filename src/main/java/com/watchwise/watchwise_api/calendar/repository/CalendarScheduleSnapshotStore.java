package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class CalendarScheduleSnapshotStore {

    private final CalendarScheduleSnapshotRepository snapshotRepository;
    private final NewTransactionExecutor newTransactionExecutor;

    public List<CalendarScheduleSnapshot> findForInterestAndMonth(
            CalendarInterest interest, java.time.YearMonth month, String region, String language) {
        return snapshotRepository.findByReleaseMonth(month, region, language).stream()
                .filter(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot()))
                .filter(snapshot -> isInInterest(snapshot, interest.sourcesByKey().keySet()))
                .toList();
    }

    public List<CalendarScheduleSnapshot> findDue(
            Collection<CalendarScheduleKey> activeKeys, LocalDateTime now) {
        Set<CalendarScheduleKey> keys = Set.copyOf(activeKeys);
        return snapshotRepository.findDueAtOrBefore(now).stream()
                .filter(snapshot -> isInInterest(snapshot, keys))
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

    private boolean isInInterest(CalendarScheduleSnapshot snapshot, Collection<CalendarScheduleKey> keys) {
        ContentType type = snapshot.getEventType() == CalendarScheduleSnapshot.EventType.MOVIE
                ? ContentType.MOVIE : ContentType.SERIES;
        String tmdbId = type == ContentType.MOVIE ? snapshot.getTmdbId() : snapshot.getSeriesTmdbId();
        return keys.contains(new CalendarScheduleKey(type, tmdbId, snapshot.getLanguage(), snapshot.getRegion()));
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
}
