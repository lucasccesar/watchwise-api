package com.watchwise.watchwise_api.calendar.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import java.util.Map;

public interface CalendarScheduleProvider {

    CalendarScheduleLookup loadMovie(String tmdbId, String region, String language);

    CalendarScheduleLookup loadSeason(String seriesTmdbId, Integer seasonNumber, String region, String language);

    CalendarScheduleLookup prepareSeason(
            String seriesTmdbId,
            Integer seasonNumber,
            String region,
            String language,
            TmdbLookupResult<TmdbSeasonFullDetails> seasonLookup,
            TmdbLookupResult<TmdbTvFullDetails> seriesLookup);

    CalendarScheduleLookup loadSeries(String seriesTmdbId, String region, String language);

    CalendarScheduleLookup loadSeriesPrepared(
            String seriesTmdbId,
            String region,
            String language,
            TmdbLookupResult<TmdbTvFullDetails> seriesLookup,
            Map<Integer, TmdbLookupResult<TmdbSeasonFullDetails>> preparedSeasonLookups);

    CalendarScheduleLookup loadSeriesFresh(String seriesTmdbId, String region, String language);
}
