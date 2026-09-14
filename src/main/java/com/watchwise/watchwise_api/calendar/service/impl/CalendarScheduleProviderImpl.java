package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieReleaseDateSelector;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.ContentType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class CalendarScheduleProviderImpl implements CalendarScheduleProvider {

    private final TmdbClient tmdbClient;
    private final Executor tmdbSeasonFetchExecutor;

    public CalendarScheduleProviderImpl(
            TmdbClient tmdbClient,
            @Qualifier("tmdbSeasonFetchExecutor") Executor tmdbSeasonFetchExecutor) {
        this.tmdbClient = tmdbClient;
        this.tmdbSeasonFetchExecutor = tmdbSeasonFetchExecutor;
    }

    @Override
    public CalendarScheduleLookup loadMovie(String tmdbId, String region, String language) {
        TmdbLookupResult<TmdbMovieReleaseDates> releaseLookup = tmdbClient.getMovieReleaseDates(tmdbId, language);
        if (!(releaseLookup instanceof TmdbLookupResult.Found<TmdbMovieReleaseDates> releases)) {
            return mapFailure(releaseLookup);
        }

        TmdbLookupResult<TmdbMovieFullDetails> detailsLookup = tmdbClient.getMovieFullDetails(tmdbId, language);
        if (!(detailsLookup instanceof TmdbLookupResult.Found<TmdbMovieFullDetails> details)) {
            return mapFailure(detailsLookup);
        }

        CalendarMovieSchedule movie = new CalendarMovieSchedule(
                tmdbId,
                region,
                language,
                CalendarMovieReleaseDateSelector.select(releases.value(), region).orElse(null),
                nonBlankOr(details.value().title(), tmdbId),
                details.value().posterPath(),
                null,
                null);
        return new CalendarScheduleLookup.Found(CalendarScheduleBatch.movie(
                new CalendarScheduleKey(ContentType.MOVIE, tmdbId, language, region), releases.origin(), movie));
    }

    @Override
    public CalendarScheduleLookup loadSeason(String seriesTmdbId, Integer seasonNumber, String region, String language) {
        if (seasonNumber == null || seasonNumber <= 0) {
            return new CalendarScheduleLookup.NotFound();
        }

        TmdbLookupResult<TmdbSeasonFullDetails> seasonLookup =
                tmdbClient.getCalendarSeasonDetails(seriesTmdbId, seasonNumber, language);
        if (!(seasonLookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> season)) {
            return seasonLookup instanceof TmdbLookupResult.NotFound<?>
                    ? new CalendarScheduleLookup.NotFound(seasonNumber)
                    : new CalendarScheduleLookup.Unavailable();
        }

        TmdbLookupResult<TmdbTvFullDetails> seriesLookup = tmdbClient.getTvFullDetails(seriesTmdbId, language);
        if (!(seriesLookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> series)) {
            return mapFailure(seriesLookup);
        }

        List<TmdbEpisodeSummary> episodes = Optional.ofNullable(season.value().episodes()).orElseGet(List::of);
        CalendarSeasonSchedule schedule = new CalendarSeasonSchedule(
                seriesTmdbId,
                seasonNumber,
                region,
                language,
                nonBlankOr(series.value().name(), seriesTmdbId),
                series.value().posterPath(),
                episodes.stream()
                        .map(this::toEpisodeSchedule)
                        .toList());
        return new CalendarScheduleLookup.Found(CalendarScheduleBatch.season(
                new CalendarScheduleKey(ContentType.SERIES, seriesTmdbId, language, region), season.origin(), schedule));
    }

    @Override
    public CalendarScheduleLookup loadSeries(String seriesTmdbId, String region, String language) {
        TmdbLookupResult<TmdbTvFullDetails> seriesLookup = tmdbClient.getTvFullDetails(seriesTmdbId, language);
        if (!(seriesLookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> series)) {
            return mapFailure(seriesLookup);
        }

        Map<Integer, Integer> expectedCounts = regularSeasonCounts(series.value());
        if (expectedCounts.values().stream().anyMatch(count -> count < 0)) {
            return new CalendarScheduleLookup.Unavailable();
        }
        List<CompletableFuture<TmdbLookupResult<CalendarSeriesSchedule.Season>>> seasonFutures = expectedCounts.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> loadSeriesSeason(seriesTmdbId, region, language, series.value(), entry.getKey(), entry.getValue()),
                        tmdbSeasonFetchExecutor))
                .toList();
        List<TmdbLookupResult<CalendarSeriesSchedule.Season>> seasonLookups = seasonFutures.stream()
                .map(CompletableFuture::join)
                .toList();
        if (seasonLookups.stream().anyMatch(TmdbLookupResult::isUnavailable)) {
            return new CalendarScheduleLookup.Unavailable();
        }
        for (int index = 0; index < seasonLookups.size(); index++) {
            if (seasonLookups.get(index) instanceof TmdbLookupResult.NotFound<?>) {
                return new CalendarScheduleLookup.NotFound(expectedCounts.keySet().stream().toList().get(index));
            }
        }
        List<CalendarSeriesSchedule.Season> seasons = seasonLookups.stream()
                .flatMap(lookup -> foundSeason(lookup).stream())
                .toList();

        return new CalendarScheduleLookup.FoundSeries(new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, seriesTmdbId, language, region),
                seasons,
                expectedCounts,
                expectedCounts.values().stream().filter(Objects::nonNull).filter(count -> count >= 0)
                        .mapToInt(Integer::intValue).sum(),
                series.origin() == TmdbLookupOrigin.REMOTE));
    }

    private TmdbLookupResult<CalendarSeriesSchedule.Season> loadSeriesSeason(
            String seriesTmdbId,
            String region,
            String language,
            TmdbTvFullDetails series,
            int seasonNumber,
            int expectedEpisodeCount) {
        TmdbLookupResult<TmdbSeasonFullDetails> lookup =
                tmdbClient.getCalendarSeasonDetails(seriesTmdbId, seasonNumber, language);
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found)) {
            return lookup instanceof TmdbLookupResult.NotFound<TmdbSeasonFullDetails>
                    ? new TmdbLookupResult.NotFound<>()
                    : new TmdbLookupResult.Unavailable<>();
        }
        List<TmdbEpisodeSummary> episodes = Optional.ofNullable(found.value().episodes()).orElseGet(List::of);
        if (episodes.size() != expectedEpisodeCount) {
            return new TmdbLookupResult.Unavailable<>();
        }
        CalendarSeasonSchedule schedule = new CalendarSeasonSchedule(
                seriesTmdbId,
                seasonNumber,
                region,
                language,
                nonBlankOr(series.name(), seriesTmdbId),
                series.posterPath(),
                episodes.stream()
                        .map(this::toEpisodeSchedule)
                        .toList());
        return new TmdbLookupResult.Found<>(
                new CalendarSeriesSchedule.Season(schedule, expectedEpisodeCount, found.origin()), found.origin());
    }

    private Optional<CalendarSeriesSchedule.Season> foundSeason(
            TmdbLookupResult<CalendarSeriesSchedule.Season> lookup) {
        return lookup instanceof TmdbLookupResult.Found<CalendarSeriesSchedule.Season> found
                ? Optional.of(found.value())
                : Optional.empty();
    }

    private Map<Integer, Integer> regularSeasonCounts(TmdbTvFullDetails series) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Optional.ofNullable(series.seasons()).orElseGet(List::of).stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.seasonNumber() != null && summary.seasonNumber() > 0)
                .sorted(Comparator.comparing(com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary::seasonNumber))
                .forEach(summary -> counts.putIfAbsent(
                        summary.seasonNumber(),
                        summary.episodeCount() == null || summary.episodeCount() < 0 ? -1 : summary.episodeCount()));
        return counts;
    }

    private CalendarEpisodeSchedule toEpisodeSchedule(TmdbEpisodeSummary episode) {
        return new CalendarEpisodeSchedule(
                episode.episodeNumber(),
                nonBlankOr(episode.name(), "Episode " + episode.episodeNumber()),
                parseDate(episode.airDate()).orElse(null),
                episode.stillPath(),
                null,
                null);
    }

    private Optional<LocalDate> parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.substring(0, 10)));
        } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
            return Optional.empty();
        }
    }

    private CalendarScheduleLookup mapFailure(TmdbLookupResult<?> lookup) {
        if (lookup instanceof TmdbLookupResult.NotFound<?>) {
            return new CalendarScheduleLookup.NotFound();
        }
        return new CalendarScheduleLookup.Unavailable();
    }

    private String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

}
