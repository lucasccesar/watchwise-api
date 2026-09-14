package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarMovieReleaseDateSelector;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class ContentScheduleReaderImpl implements ContentScheduleReader {

    private final TmdbClient tmdbClient;
    private final Executor tmdbSeasonFetchExecutor;

    public ContentScheduleReaderImpl(
            TmdbClient tmdbClient,
            @Qualifier("tmdbSeasonFetchExecutor") Executor tmdbSeasonFetchExecutor) {
        this.tmdbClient = tmdbClient;
        this.tmdbSeasonFetchExecutor = tmdbSeasonFetchExecutor;
    }

    @Override
    public ContentScheduleLookup readMovie(String tmdbId, String region, String language) {
        TmdbLookupResult<TmdbMovieFullDetails> detailsLookup = tmdbClient.getMovieFullDetails(tmdbId, language);
        if (!(detailsLookup instanceof TmdbLookupResult.Found<TmdbMovieFullDetails> details)
                || details.value() == null) {
            return mapFailure(detailsLookup);
        }

        TmdbLookupResult<TmdbMovieReleaseDates> releaseLookup = tmdbClient.getMovieReleaseDates(tmdbId, language);
        if (releaseLookup instanceof TmdbLookupResult.Found<TmdbMovieReleaseDates> found) {
            return new ContentScheduleLookup.Found(
                    new ContentSchedule(
                            ContentScheduleKey.movie(tmdbId),
                            CalendarMovieReleaseDateSelector.select(found.value(), region).orElse(null),
                            details.value().status(),
                            List.of(),
                            true,
                            false,
                            nonBlankOr(details.value().title(), tmdbId),
                            details.value().posterPath(),
                            Map.of()),
                    found.origin());
        }
        if (releaseLookup instanceof TmdbLookupResult.NotFound<?>) {
            return new ContentScheduleLookup.NotFound();
        }

        return new ContentScheduleLookup.Found(
                new ContentSchedule(
                        ContentScheduleKey.movie(tmdbId),
                        null,
                        details.value().status(),
                        List.of(),
                        true,
                        true,
                        nonBlankOr(details.value().title(), tmdbId),
                        details.value().posterPath(),
                        Map.of()),
                details.origin());
    }

    @Override
    public ContentScheduleLookup readSeason(String seriesTmdbId, Integer seasonNumber, String region, String language) {
        if (seasonNumber == null || seasonNumber <= 0) {
            return new ContentScheduleLookup.NotFound(seasonNumber);
        }

        TmdbLookupResult<TmdbSeasonFullDetails> seasonLookup =
                tmdbClient.getSeasonFullDetails(seriesTmdbId, seasonNumber, language);
        if (!(seasonLookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> season)
                || season.value() == null) {
            return mapFailure(seasonLookup, seasonNumber);
        }

        TmdbLookupResult<TmdbTvFullDetails> seriesLookup = tmdbClient.getTvFullDetails(seriesTmdbId, language);
        if (!(seriesLookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> series)
                || series.value() == null) {
            return mapFailure(seriesLookup);
        }

        List<TmdbEpisodeSummary> tmdbEpisodes = season.value().episodes();
        List<ContentScheduleEpisode> episodes = toEpisodes(seriesTmdbId, seasonNumber, tmdbEpisodes);
        ContentSchedule schedule = new ContentSchedule(
                ContentScheduleKey.season(seriesTmdbId, seasonNumber),
                parseDate(season.value().airDate()),
                series.value().status(),
                episodes,
                isTrustedEpisodeList(tmdbEpisodes),
                false,
                nonBlankOr(series.value().name(), seriesTmdbId),
                series.value().posterPath(),
                Map.of());
        return new ContentScheduleLookup.Found(
                schedule,
                season.origin(),
                Map.of(seasonNumber, season.origin()));
    }

    @Override
    public ContentScheduleLookup readSeries(String seriesTmdbId, String region, String language) {
        TmdbLookupResult<TmdbTvFullDetails> seriesLookup = tmdbClient.getTvFullDetails(seriesTmdbId, language);
        if (!(seriesLookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> series)
                || series.value() == null) {
            return mapFailure(seriesLookup);
        }

        TmdbTvFullDetails details = series.value();
        Map<Integer, Integer> expectedCounts = regularSeasonCounts(details);
        boolean complete = details.seasons() != null
                && regularSeasonSummariesAreTrustworthy(details);
        List<CompletableFuture<TmdbLookupResult<TmdbSeasonFullDetails>>> seasonFutures = expectedCounts.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> tmdbClient.getSeasonFullDetails(seriesTmdbId, entry.getKey(), language),
                        tmdbSeasonFetchExecutor))
                .toList();
        List<TmdbLookupResult<TmdbSeasonFullDetails>> seasonLookups = seasonFutures.stream()
                .map(CompletableFuture::join)
                .toList();

        List<ContentScheduleEpisode> episodes = new ArrayList<>();
        Map<Integer, TmdbLookupOrigin> seasonOrigins = new LinkedHashMap<>();
        List<Integer> seasonNumbers = new ArrayList<>(expectedCounts.keySet());
        for (int index = 0; index < seasonLookups.size(); index++) {
            Integer seasonNumber = seasonNumbers.get(index);
            TmdbLookupResult<TmdbSeasonFullDetails> lookup = seasonLookups.get(index);
            if (lookup instanceof TmdbLookupResult.NotFound<?>) {
                return new ContentScheduleLookup.NotFound(seasonNumber);
            }
            if (!(lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found)
                    || found.value() == null) {
                return new ContentScheduleLookup.Unavailable();
            }

            List<TmdbEpisodeSummary> tmdbEpisodes = found.value().episodes();
            episodes.addAll(toEpisodes(seriesTmdbId, seasonNumber, tmdbEpisodes));
            seasonOrigins.put(seasonNumber, found.origin());
            if (!isTrustedEpisodeList(tmdbEpisodes) || tmdbEpisodes.size() != expectedCounts.get(seasonNumber)) {
                complete = false;
            }
        }

        ContentSchedule schedule = new ContentSchedule(
                ContentScheduleKey.series(seriesTmdbId),
                parseDate(details.firstAirDate()),
                details.status(),
                episodes,
                complete,
                false,
                nonBlankOr(details.name(), seriesTmdbId),
                details.posterPath(),
                expectedCounts);
        return new ContentScheduleLookup.Found(schedule, series.origin(), seasonOrigins);
    }

    private Map<Integer, Integer> regularSeasonCounts(TmdbTvFullDetails series) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        Optional.ofNullable(series.seasons()).orElseGet(List::of).stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.seasonNumber() != null && summary.seasonNumber() > 0)
                .sorted(Comparator.comparing(TmdbSeasonSummary::seasonNumber))
                .forEach(summary -> {
                    Integer episodeCount = summary.episodeCount();
                    if (episodeCount != null && episodeCount >= 0) {
                        counts.putIfAbsent(summary.seasonNumber(), episodeCount);
                    }
                });
        return counts;
    }

    private boolean regularSeasonSummariesAreTrustworthy(TmdbTvFullDetails series) {
        return Optional.ofNullable(series.seasons()).orElseGet(List::of).stream()
                .filter(Objects::nonNull)
                .filter(summary -> summary.seasonNumber() != null && summary.seasonNumber() > 0)
                .allMatch(summary -> summary.episodeCount() != null && summary.episodeCount() >= 0);
    }

    private List<ContentScheduleEpisode> toEpisodes(
            String seriesTmdbId,
            Integer seasonNumber,
            List<TmdbEpisodeSummary> tmdbEpisodes) {
        if (tmdbEpisodes == null) {
            return List.of();
        }
        return tmdbEpisodes.stream()
                .filter(Objects::nonNull)
                .map(episode -> new ContentScheduleEpisode(
                        seriesTmdbId,
                        seasonNumber,
                        episode.episodeNumber(),
                        parseDate(episode.airDate()),
                        nonBlankOr(episode.name(), fallbackEpisodeTitle(episode.episodeNumber())),
                        episode.stillPath()))
                .toList();
    }

    private boolean isTrustedEpisodeList(List<TmdbEpisodeSummary> episodes) {
        return episodes != null
                && episodes.stream().allMatch(episode -> episode != null
                && episode.episodeNumber() != null
                && episode.episodeNumber() > 0)
                && episodes.stream().map(TmdbEpisodeSummary::episodeNumber).distinct().count() == episodes.size();
    }

    private ContentScheduleLookup mapFailure(TmdbLookupResult<?> lookup) {
        return mapFailure(lookup, null);
    }

    private ContentScheduleLookup mapFailure(TmdbLookupResult<?> lookup, Integer seasonNumber) {
        if (lookup instanceof TmdbLookupResult.NotFound<?>) {
            return new ContentScheduleLookup.NotFound(seasonNumber);
        }
        return new ContentScheduleLookup.Unavailable();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private String fallbackEpisodeTitle(Integer episodeNumber) {
        return episodeNumber == null ? "Episode" : "Episode " + episodeNumber;
    }

    private String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
