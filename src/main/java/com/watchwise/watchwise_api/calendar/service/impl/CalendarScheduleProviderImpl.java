package com.watchwise.watchwise_api.calendar.service.impl;

import com.watchwise.watchwise_api.calendar.service.CalendarEpisodeSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarMovieSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleBatch;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleKey;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleLookup;
import com.watchwise.watchwise_api.calendar.service.CalendarScheduleProvider;
import com.watchwise.watchwise_api.calendar.service.CalendarSeasonSchedule;
import com.watchwise.watchwise_api.calendar.service.CalendarSeriesSchedule;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.ContentType;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CalendarScheduleProviderImpl implements CalendarScheduleProvider {

    /* TMDB release types: theatrical wide, limited, premiere, digital, physical, then TV. */
    private static final List<Integer> RELEASE_TYPE_PRECEDENCE = List.of(3, 2, 1, 4, 5, 6);

    private final TmdbClient tmdbClient;
    private final Executor tmdbSeasonFetchExecutor;

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
                selectRegionalReleaseDate(releases.value(), region).orElse(null),
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
            return mapFailure(seasonLookup);
        }

        TmdbLookupResult<TmdbTvFullDetails> seriesLookup = tmdbClient.getTvFullDetails(seriesTmdbId, language);
        if (!(seriesLookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> series)) {
            return mapFailure(seriesLookup);
        }

        CalendarSeasonSchedule schedule = new CalendarSeasonSchedule(
                seriesTmdbId,
                seasonNumber,
                region,
                language,
                nonBlankOr(series.value().name(), seriesTmdbId),
                series.value().posterPath(),
                Optional.ofNullable(season.value().episodes()).orElseGet(List::of).stream()
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
        List<CalendarSeriesSchedule.Season> seasons = expectedCounts.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> loadSeriesSeason(seriesTmdbId, region, language, series.value(), entry.getKey(), entry.getValue()),
                        tmdbSeasonFetchExecutor))
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();

        return new CalendarScheduleLookup.FoundSeries(new CalendarSeriesSchedule(
                new CalendarScheduleKey(ContentType.SERIES, seriesTmdbId, language, region),
                seasons,
                expectedCounts,
                expectedCounts.values().stream().filter(Objects::nonNull).filter(count -> count >= 0)
                        .mapToInt(Integer::intValue).sum()));
    }

    private Optional<CalendarSeriesSchedule.Season> loadSeriesSeason(
            String seriesTmdbId,
            String region,
            String language,
            TmdbTvFullDetails series,
            int seasonNumber,
            int expectedEpisodeCount) {
        TmdbLookupResult<TmdbSeasonFullDetails> lookup =
                tmdbClient.getCalendarSeasonDetails(seriesTmdbId, seasonNumber, language);
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found)) {
            return Optional.empty();
        }
        CalendarSeasonSchedule schedule = new CalendarSeasonSchedule(
                seriesTmdbId,
                seasonNumber,
                region,
                language,
                nonBlankOr(series.name(), seriesTmdbId),
                series.posterPath(),
                Optional.ofNullable(found.value().episodes()).orElseGet(List::of).stream()
                        .map(this::toEpisodeSchedule)
                        .toList());
        return Optional.of(new CalendarSeriesSchedule.Season(schedule, expectedEpisodeCount, found.origin()));
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

    private Optional<LocalDate> selectRegionalReleaseDate(TmdbMovieReleaseDates releases, String region) {
        return Optional.ofNullable(releases.results()).orElseGet(List::of).stream()
                .filter(candidate -> region.equals(candidate.isoCode()))
                .findFirst()
                .flatMap(this::selectReleaseDate);
    }

    private Optional<LocalDate> selectReleaseDate(TmdbRegionReleaseDates region) {
        return Optional.ofNullable(region.releaseDates()).orElseGet(List::of).stream()
                .map(release -> new RankedDate(release, parseDate(release.releaseDate())))
                .filter(ranked -> ranked.date().isPresent())
                .sorted(Comparator.comparingInt((RankedDate ranked) -> releaseTypeRank(ranked.release().type()))
                        .thenComparing(ranked -> ranked.date().orElseThrow()))
                .map(ranked -> ranked.date().orElseThrow())
                .findFirst();
    }

    private int releaseTypeRank(Integer type) {
        int rank = RELEASE_TYPE_PRECEDENCE.indexOf(type);
        return rank >= 0 ? rank : RELEASE_TYPE_PRECEDENCE.size();
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

    private record RankedDate(TmdbMovieReleaseDate release, Optional<LocalDate> date) {
    }
}
