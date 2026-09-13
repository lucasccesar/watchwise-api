package com.watchwise.watchwise_api.calendar.service;

public interface CalendarScheduleProvider {

    CalendarScheduleLookup loadMovie(String tmdbId, String region, String language);

    CalendarScheduleLookup loadSeason(String seriesTmdbId, Integer seasonNumber, String region, String language);

    CalendarScheduleLookup loadSeries(String seriesTmdbId, String region, String language);
}
