package com.watchwise.watchwise_api.calendar.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.watchwise.watchwise_api.calendar.repository.CalendarScheduleSnapshotStore;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleCadence;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleSynchronizer;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class CalendarScheduleSynchronizerImpl implements CalendarScheduleSynchronizer {

    private final CalendarScheduleSnapshotStore snapshotStore;
    private final Cache<String, TmdbLookupResult<TmdbMovieReleaseDates>> movieScheduleCache;
    private final Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> seasonScheduleCache;

    public CalendarScheduleSynchronizerImpl(
            CalendarScheduleSnapshotStore snapshotStore,
            @Qualifier("tmdbMovieReleaseDatesCache")
            Cache<String, TmdbLookupResult<TmdbMovieReleaseDates>> movieScheduleCache,
            @Qualifier("tmdbCalendarSeasonDetailsCache")
            Cache<String, TmdbLookupResult<TmdbSeasonFullDetails>> seasonScheduleCache) {
        this.snapshotStore = snapshotStore;
        this.movieScheduleCache = movieScheduleCache;
        this.seasonScheduleCache = seasonScheduleCache;
    }

    @Override
    public void synchronize(CalendarScheduleBatch batch, Instant checkedAt) {
        if (batch.origin() == TmdbLookupOrigin.CACHE) {
            return;
        }

        if (batch.movie() != null) {
            CalendarMovieSchedule movie = batch.movie().withCheckTimes(
                    checkedAt, CalendarScheduleCadence.nextCheckAt(batch.movie().releaseDate(), checkedAt));
            if (snapshotStore.upsertMovie(movie)) {
                movieScheduleCache.invalidate(batch.movie().tmdbId() + "|" + batch.movie().language());
            }
            return;
        }

        CalendarSeasonSchedule season = batch.season().withCheckTimes(
                checkedAt, releaseDate -> CalendarScheduleCadence.nextCheckAt(releaseDate, checkedAt));
        if (snapshotStore.reconcileSeason(season)) {
            seasonScheduleCache.invalidate(
                    batch.season().seriesTmdbId() + "|" + batch.season().seasonNumber() + "|" + batch.season().language());
        }
    }

    @Override
    public void synchronizeSeries(CalendarSeriesSchedule schedule, Instant checkedAt) {
        if (!schedule.hasRemoteResults()) {
            return;
        }
        CalendarSeriesSchedule checkedSchedule = new CalendarSeriesSchedule(
                schedule.key(),
                schedule.seasons().stream()
                        .map(season -> new CalendarSeriesSchedule.Season(
                                season.schedule().withCheckTimes(
                                        checkedAt,
                                        releaseDate -> CalendarScheduleCadence.nextCheckAt(releaseDate, checkedAt)),
                                season.expectedEpisodeCount(),
                                season.origin()))
                        .toList(),
                schedule.expectedEpisodeCountsBySeason(),
                schedule.totalRegularEpisodeCount(),
                schedule.completeSchedule());
        if (snapshotStore.reconcileSeries(checkedSchedule, checkedAt)) {
            checkedSchedule.seasons().stream()
                    .filter(season -> season.origin() == TmdbLookupOrigin.REMOTE)
                    .forEach(season -> seasonScheduleCache.invalidate(
                            season.schedule().seriesTmdbId() + "|" + season.schedule().seasonNumber()
                                    + "|" + season.schedule().language()));
        }
    }
}
