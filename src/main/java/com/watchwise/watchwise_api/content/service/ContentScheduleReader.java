package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import java.util.Map;

public interface ContentScheduleReader {

    ContentScheduleLookup readMovie(String tmdbId, String region, String language);

    ContentScheduleLookup readSeason(String seriesTmdbId, Integer seasonNumber, String region, String language);

    ContentScheduleLookup readSeries(String seriesTmdbId, String region, String language);

    ContentScheduleLookup readCalendarSeason(String seriesTmdbId, Integer seasonNumber, String region, String language);

    ContentScheduleLookup readCalendarSeasonFresh(
            String seriesTmdbId, Integer seasonNumber, String region, String language);

    ContentScheduleLookup readCalendarSeason(
            String seriesTmdbId,
            Integer seasonNumber,
            String region,
            String language,
            TmdbLookupResult<TmdbSeasonFullDetails> seasonLookup,
            TmdbLookupResult<TmdbTvFullDetails> seriesLookup);

    ContentScheduleLookup readCalendarSeries(String seriesTmdbId, String region, String language);

    ContentScheduleLookup readCalendarSeries(
            String seriesTmdbId,
            String region,
            String language,
            TmdbLookupResult<TmdbTvFullDetails> seriesLookup,
            Map<Integer, TmdbLookupResult<TmdbSeasonFullDetails>> preparedSeasonLookups);

    ContentScheduleLookup readCalendarSeriesFresh(String seriesTmdbId, String region, String language);
}
