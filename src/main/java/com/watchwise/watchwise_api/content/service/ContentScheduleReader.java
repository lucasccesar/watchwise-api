package com.watchwise.watchwise_api.content.service;

public interface ContentScheduleReader {

    ContentScheduleLookup readMovie(String tmdbId, String region, String language);

    ContentScheduleLookup readSeason(String seriesTmdbId, Integer seasonNumber, String region, String language);

    ContentScheduleLookup readSeries(String seriesTmdbId, String region, String language);
}
