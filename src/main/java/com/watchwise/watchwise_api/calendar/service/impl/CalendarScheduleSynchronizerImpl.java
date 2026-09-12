package com.watchwise.watchwise_api.calendar.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleSynchronizer;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class CalendarScheduleSynchronizerImpl implements CalendarScheduleSynchronizer {

    private static final int NEAR_FUTURE_DAYS = 7;

    private final CalendarScheduleSnapshotStore snapshotStore;
    @Qualifier("tmdbMovieReleaseDatesCache")
    private final Cache<String, TmdbLookupResult<TmdbMovieReleaseDates>> movieScheduleCache;
    @Qualifier("tmdbCalendarSeasonDetailsCache")
    private final Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> seasonScheduleCache;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void synchronize(CalendarScheduleBatch batch, Instant checkedAt) {
        if (batch.origin() == TmdbLookupOrigin.CACHE) {
            return;
        }

        if (batch.movie() != null) {
            CalendarMovieSchedule movie = batch.movie().withCheckTimes(checkedAt, nextCheckAt(batch.movie().releaseDate(), checkedAt));
            snapshotStore.upsertMovie(movie);
            invalidateAfterCommit(() -> movieScheduleCache.invalidate(batch.movie().tmdbId() + "|" + batch.movie().language()));
            return;
        }

        CalendarSeasonSchedule season = batch.season().withCheckTimes(
                checkedAt, releaseDate -> nextCheckAt(releaseDate, checkedAt));
        snapshotStore.reconcileSeason(season);
        invalidateAfterCommit(() -> seasonScheduleCache.invalidate(
                batch.season().seriesTmdbId() + "|" + batch.season().seasonNumber() + "|" + batch.season().language()));
    }

    private Instant nextCheckAt(LocalDate releaseDate, Instant checkedAt) {
        if (releaseDate == null) {
            return checkedAt.plusSeconds(7 * 24 * 60 * 60);
        }
        LocalDate today = checkedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        if (!releaseDate.isAfter(today)) {
            return Instant.MAX;
        }
        return releaseDate.isAfter(today.plusDays(NEAR_FUTURE_DAYS))
                ? checkedAt.plusSeconds(7 * 24 * 60 * 60)
                : checkedAt.plusSeconds(24 * 60 * 60);
    }

    private void invalidateAfterCommit(Runnable invalidation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidation.run();
            }
        });
    }
}
