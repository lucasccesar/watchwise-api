package com.watchwise.watchwise_api.calendar.repository;

import com.watchwise.watchwise_api.calendar.entity.CalendarScheduleSnapshot;
import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarInterest;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.ContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

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

    private static final int NEAR_FUTURE_DAYS = 7;

    private final CalendarScheduleSnapshotRepository snapshotRepository;
    private final NewTransactionExecutor newTransactionExecutor;

    public CalendarScheduleSnapshotStore(CalendarScheduleSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
        this.newTransactionExecutor = null;
    }

    @Transactional(readOnly = true)
    public List<CalendarScheduleSnapshot> findForInterestAndMonth(
            CalendarInterest interest, java.time.YearMonth month, String region, String language) {
        return snapshotRepository.findByReleaseMonth(month, region, language).stream()
                .filter(snapshot -> Boolean.TRUE.equals(snapshot.getPresentInLastTmdbSnapshot()))
                .filter(snapshot -> isInInterest(snapshot, interest.sourcesByKey().keySet()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CalendarScheduleSnapshot> findDue(
            Collection<CalendarScheduleKey> activeKeys, LocalDateTime now) {
        Set<CalendarScheduleKey> keys = Set.copyOf(activeKeys);
        return snapshotRepository.findDueAtOrBefore(now).stream()
                .filter(snapshot -> isInInterest(snapshot, keys))
                .toList();
    }

    @Transactional
    public void upsertMovie(CalendarMovieSchedule schedule) {
        Optional<CalendarScheduleSnapshot> existing = snapshotRepository.findByMovieIdentity(
                schedule.tmdbId(), schedule.region(), schedule.language());
        if (existing.isPresent()) {
            snapshotRepository.saveAndFlush(mergeMovie(existing.get(), schedule));
            return;
        }
        try {
            saveInIsolatedTransaction(newMovie(schedule));
        } catch (DataIntegrityViolationException exception) {
            CalendarScheduleSnapshot winner = snapshotRepository.findByMovieIdentity(
                    schedule.tmdbId(), schedule.region(), schedule.language()).orElseThrow(() -> exception);
            snapshotRepository.saveAndFlush(mergeMovie(winner, schedule));
        }
    }

    @Transactional
    public void reconcileSeason(CalendarSeasonSchedule schedule) {
        boolean hasUsableDate = schedule.episodes().stream().anyMatch(episode -> episode.releaseDate() != null);
        if (hasUsableDate) {
            Set<Integer> coordinates = Set.copyOf(schedule.episodeCoordinates());
            snapshotsForSeason(schedule).stream()
                    .filter(snapshot -> !coordinates.contains(snapshot.getEpisodeNumber()))
                    .forEach(snapshotRepository::delete);
        }

        for (CalendarEpisodeSchedule episode : schedule.episodes()) {
            if (episode.episodeNumber() == null || episode.episodeNumber() <= 0) {
                continue;
            }
            Optional<CalendarScheduleSnapshot> existing = snapshotRepository.findByEpisodeIdentity(
                    schedule.seriesTmdbId(), schedule.seasonNumber(), episode.episodeNumber(),
                    schedule.region(), schedule.language());
            if (existing.isPresent()) {
                snapshotRepository.saveAndFlush(mergeEpisode(existing.get(), schedule, episode));
                continue;
            }
            try {
                saveInIsolatedTransaction(newEpisode(schedule, episode));
            } catch (DataIntegrityViolationException exception) {
                CalendarScheduleSnapshot winner = snapshotRepository.findByEpisodeIdentity(
                        schedule.seriesTmdbId(), schedule.seasonNumber(), episode.episodeNumber(),
                        schedule.region(), schedule.language()).orElseThrow(() -> exception);
                snapshotRepository.saveAndFlush(mergeEpisode(winner, schedule, episode));
            }
        }
    }

    private void saveInIsolatedTransaction(CalendarScheduleSnapshot snapshot) {
        if (newTransactionExecutor == null) {
            snapshotRepository.saveAndFlush(snapshot);
            return;
        }
        newTransactionExecutor.runInNewTransaction(() -> snapshotRepository.saveAndFlush(snapshot));
    }

    private List<CalendarScheduleSnapshot> snapshotsForSeason(CalendarSeasonSchedule schedule) {
        return snapshotRepository.findAll().stream()
                .filter(snapshot -> snapshot.getEventType() == CalendarScheduleSnapshot.EventType.EPISODE)
                .filter(snapshot -> Objects.equals(snapshot.getSeriesTmdbId(), schedule.seriesTmdbId()))
                .filter(snapshot -> Objects.equals(snapshot.getSeasonNumber(), schedule.seasonNumber()))
                .filter(snapshot -> Objects.equals(snapshot.getRegion(), schedule.region()))
                .filter(snapshot -> Objects.equals(snapshot.getLanguage(), schedule.language()))
                .toList();
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

    private LocalDateTime nextCheckAt(LocalDate releaseDate, Instant checkedAt) {
        LocalDateTime checked = toLocalDateTime(checkedAt);
        if (releaseDate == null) {
            return checked.plusDays(7);
        }
        LocalDate today = checkedAt.atZone(ZoneOffset.UTC).toLocalDate();
        if (!releaseDate.isAfter(today)) {
            return LocalDateTime.MAX;
        }
        return releaseDate.isAfter(today.plusDays(NEAR_FUTURE_DAYS)) ? checked.plusDays(7) : checked.plusDays(1);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
}
