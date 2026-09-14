package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class CalendarScheduleProviderImpl implements CalendarScheduleProvider {

    private final ContentScheduleReader scheduleReader;

    public CalendarScheduleProviderImpl(ContentScheduleReader scheduleReader) {
        this.scheduleReader = scheduleReader;
    }

    @Override
    public CalendarScheduleLookup loadMovie(String tmdbId, String region, String language) {
        return mapMovie(scheduleReader.readMovie(tmdbId, region, language), tmdbId, region, language);
    }

    @Override
    public CalendarScheduleLookup loadSeason(String seriesTmdbId, Integer seasonNumber, String region, String language) {
        if (seasonNumber == null || seasonNumber <= 0) {
            return new CalendarScheduleLookup.NotFound();
        }
        return mapSeason(
                scheduleReader.readSeason(seriesTmdbId, seasonNumber, region, language),
                seriesTmdbId,
                seasonNumber,
                region,
                language);
    }

    @Override
    public CalendarScheduleLookup loadSeries(String seriesTmdbId, String region, String language) {
        return mapSeries(scheduleReader.readSeries(seriesTmdbId, region, language), seriesTmdbId, region, language);
    }

    private CalendarScheduleLookup mapMovie(
            ContentScheduleLookup lookup,
            String tmdbId,
            String region,
            String language) {
        if (lookup instanceof ContentScheduleLookup.NotFound) {
            return new CalendarScheduleLookup.NotFound();
        }
        if (!(lookup instanceof ContentScheduleLookup.Found found)) {
            return new CalendarScheduleLookup.Unavailable();
        }
        ContentSchedule schedule = found.schedule();
        if (schedule.releaseDateLookupUnavailable()) {
            return new CalendarScheduleLookup.Unavailable();
        }
        CalendarMovieSchedule movie = new CalendarMovieSchedule(
                tmdbId,
                region,
                language,
                schedule.releaseDate(),
                nonBlankOr(schedule.title(), tmdbId),
                schedule.posterPath(),
                null,
                null);
        return new CalendarScheduleLookup.Found(CalendarScheduleBatch.movie(
                new CalendarScheduleKey(ContentType.MOVIE, tmdbId, language, region), found.origin(), movie));
    }

    private CalendarScheduleLookup mapSeason(
            ContentScheduleLookup lookup,
            String seriesTmdbId,
            Integer seasonNumber,
            String region,
            String language) {
        if (lookup instanceof ContentScheduleLookup.NotFound notFound) {
            return new CalendarScheduleLookup.NotFound(
                    notFound.seasonNumber() == null ? seasonNumber : notFound.seasonNumber());
        }
        if (!(lookup instanceof ContentScheduleLookup.Found found)) {
            return new CalendarScheduleLookup.Unavailable();
        }
        ContentSchedule schedule = found.schedule();
        CalendarSeasonSchedule season = new CalendarSeasonSchedule(
                seriesTmdbId,
                seasonNumber,
                region,
                language,
                nonBlankOr(schedule.title(), seriesTmdbId),
                schedule.posterPath(),
                schedule.episodes().stream().map(this::toCalendarEpisode).toList());
        return new CalendarScheduleLookup.Found(CalendarScheduleBatch.season(
                new CalendarScheduleKey(ContentType.SERIES, seriesTmdbId, language, region), found.origin(), season));
    }

    private CalendarScheduleLookup mapSeries(
            ContentScheduleLookup lookup,
            String seriesTmdbId,
            String region,
            String language) {
        if (lookup instanceof ContentScheduleLookup.NotFound notFound) {
            return new CalendarScheduleLookup.NotFound(notFound.seasonNumber());
        }
        if (!(lookup instanceof ContentScheduleLookup.Found found)) {
            return new CalendarScheduleLookup.Unavailable();
        }
        ContentSchedule schedule = found.schedule();
        if (!schedule.complete()) {
            return new CalendarScheduleLookup.Unavailable();
        }

        Map<Integer, Integer> expectedCounts = new LinkedHashMap<>();
        schedule.expectedEpisodeCountsBySeason().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> expectedCounts.put(entry.getKey(), entry.getValue()));
        List<CalendarSeriesSchedule.Season> seasons = expectedCounts.entrySet().stream()
                .map(entry -> toCalendarSeriesSeason(schedule, found, entry.getKey(), entry.getValue(), region, language))
                .toList();
        if (seasons.stream().anyMatch(Objects::isNull)) {
            return new CalendarScheduleLookup.Unavailable();
        }
        int totalEpisodeCount = expectedCounts.values().stream().mapToInt(Integer::intValue).sum();
        CalendarSeriesSchedule scheduleResult = new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, seriesTmdbId, language, region),
                seasons,
                expectedCounts,
                totalEpisodeCount,
                found.origin() == TmdbLookupOrigin.REMOTE);
        return new CalendarScheduleLookup.FoundSeries(scheduleResult);
    }

    private CalendarSeriesSchedule.Season toCalendarSeriesSeason(
            ContentSchedule schedule,
            ContentScheduleLookup.Found found,
            Integer seasonNumber,
            Integer expectedEpisodeCount,
            String region,
            String language) {
        List<ContentScheduleEpisode> sourceEpisodes = schedule.episodes().stream()
                .filter(episode -> seasonNumber.equals(episode.seasonNumber()))
                .toList();
        if (sourceEpisodes.size() != expectedEpisodeCount
                || sourceEpisodes.stream().anyMatch(episode -> episode.episodeNumber() == null || episode.episodeNumber() <= 0)
                || sourceEpisodes.stream().map(ContentScheduleEpisode::episodeNumber).distinct().count() != sourceEpisodes.size()) {
            return null;
        }
        CalendarSeasonSchedule season = new CalendarSeasonSchedule(
                schedule.seriesTmdbId(),
                seasonNumber,
                region,
                language,
                nonBlankOr(schedule.title(), schedule.seriesTmdbId()),
                schedule.posterPath(),
                sourceEpisodes.stream().map(this::toCalendarEpisode).toList());
        TmdbLookupOrigin origin = found.seasonOriginsByNumber().getOrDefault(seasonNumber, found.origin());
        return new CalendarSeriesSchedule.Season(season, expectedEpisodeCount, origin);
    }

    private CalendarEpisodeSchedule toCalendarEpisode(ContentScheduleEpisode episode) {
        return new CalendarEpisodeSchedule(
                episode.episodeNumber(),
                nonBlankOr(episode.title(), fallbackEpisodeTitle(episode.episodeNumber())),
                episode.releaseDate(),
                episode.stillPath(),
                null,
                null);
    }

    private String fallbackEpisodeTitle(Integer episodeNumber) {
        return episodeNumber == null ? "Episode" : "Episode " + episodeNumber;
    }

    private String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
