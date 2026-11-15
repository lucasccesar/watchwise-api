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
import java.util.HashSet;

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
    public boolean synchronize(CalendarScheduleBatch batch, Instant checkedAt) {
        if (batch == null || batch.origin() != TmdbLookupOrigin.REMOTE) {
            return false;
        }

        if (batch.movie() != null) {
            CalendarMovieSchedule movie = batch.movie().withCheckTimes(
                    checkedAt, CalendarScheduleCadence.nextCheckAt(batch.movie().releaseDate(), checkedAt));
            if (snapshotStore.upsertMovie(movie)) {
                movieScheduleCache.invalidate(batch.movie().tmdbId() + "|" + batch.movie().language());
            }
            return true;
        }

        CalendarSeasonSchedule sourceSeason = batch.season();
        if (sourceSeason == null
                || batch.expectedEpisodeCount() == null
                || batch.expectedEpisodeCount() <= 0
                || batch.expectedEpisodeCount() != sourceSeason.episodes().size()
                || batch.key().type() != com.watchwise.watchwise_api.content.entity.ContentType.SERIES
                || !batch.key().tmdbId().equals(sourceSeason.seriesTmdbId())
                || !batch.key().preferredRegion().equals(sourceSeason.region())
                || !batch.key().preferredLanguage().equals(sourceSeason.language())
                || sourceSeason.seasonNumber() == null || sourceSeason.seasonNumber() <= 0
                || sourceSeason.episodes().stream().anyMatch(episode -> episode.episodeNumber() == null
                        || episode.episodeNumber() <= 0 || episode.releaseDate() == null)
                || new HashSet<>(sourceSeason.episodeCoordinates()).size() != sourceSeason.episodes().size()) {
            return false;
        }

        CalendarSeasonSchedule season = sourceSeason.withCheckTimes(
                checkedAt, releaseDate -> CalendarScheduleCadence.nextCheckAt(releaseDate, checkedAt));
        CalendarScheduleSnapshotStore.SeasonReconciliationResult result =
                snapshotStore.reconcileSeasonWithStatus(season, checkedAt);
        if (result.factsChanged()) {
            seasonScheduleCache.invalidate(
                    batch.season().seriesTmdbId() + "|" + batch.season().seasonNumber() + "|" + batch.season().language());
        }
        return result.accepted();
    }

    @Override
    public void synchronizeSeries(CalendarSeriesSchedule schedule, Instant checkedAt) {
        if (!schedule.hasRemoteSummaryAuthority() && !schedule.hasRemoteSeasonPayloads()) {
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
