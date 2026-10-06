package com.watchwise.watchwise_api.seriesprogress.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressNextEpisodeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SeriesProgressNextEpisodeResolverImpl implements SeriesProgressNextEpisodeResolver {

    private final TmdbClient tmdbClient;

    @Override
    public ProgressEpisodeDTO resolveNext(
            TmdbTvFullDetails seriesDetails,
            String language,
            List<SeasonProgressDTO> seasonProgress,
            Set<WatchedEpisodeCoordinate> watchedCoordinates,
            LocalDate today) {
        if (seriesDetails == null || seriesDetails.id() == null || seriesDetails.seasons() == null) {
            return null;
        }

        Map<Integer, SeasonProgressDTO> progressBySeason = seasonProgress == null
                ? Map.of()
                : seasonProgress.stream()
                        .filter(Objects::nonNull)
                        .filter(progress -> progress.seasonNumber() != null)
                        .collect(Collectors.toMap(SeasonProgressDTO::seasonNumber, Function.identity(), (first, ignored) -> first));

        List<TmdbSeasonSummary> regularSeasons = seriesDetails.seasons().stream()
                .filter(Objects::nonNull)
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                .sorted(Comparator.comparing(TmdbSeasonSummary::seasonNumber))
                .toList();

        for (TmdbSeasonSummary season : regularSeasons) {
            SeasonProgressDTO progress = progressBySeason.get(season.seasonNumber());
            if (progress != null && isComplete(progress)) {
                continue;
            }
            ProgressEpisodeDTO next = resolveFromSeason(
                    seriesDetails.id(), season.seasonNumber(), language,
                    watchedCoordinates == null ? Set.of() : watchedCoordinates, today);
            if (next != null) {
                return next;
            }
        }
        return null;
    }

    private ProgressEpisodeDTO resolveFromSeason(
            String seriesTmdbId,
            Integer seasonNumber,
            String language,
            Set<WatchedEpisodeCoordinate> watchedCoordinates,
            LocalDate today) {
        TmdbLookupResult<TmdbSeasonFullDetails> lookup = tmdbClient
                .getSeasonFullDetails(seriesTmdbId, seasonNumber, language);
        if (lookup instanceof TmdbLookupResult.Unavailable<TmdbSeasonFullDetails>) {
            throw new TmdbUnavailableException("TMDB is unavailable while resolving the next episode");
        }
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found)
                || found.value().episodes() == null) {
            return null;
        }

        return found.value().episodes().stream()
                .filter(Objects::nonNull)
                .filter(episode -> episode.episodeNumber() != null && episode.episodeNumber() > 0)
                .sorted(Comparator.comparing(TmdbEpisodeSummary::episodeNumber))
                .filter(episode -> !watchedCoordinates.contains(
                        new WatchedEpisodeCoordinate(seriesTmdbId, seasonNumber, episode.episodeNumber())))
                .map(episode -> toProgressEpisode(seasonNumber, episode, today))
                .findFirst()
                .orElse(null);
    }

    private ProgressEpisodeDTO toProgressEpisode(
            Integer seasonNumber,
            TmdbEpisodeSummary episode,
            LocalDate today) {
        LocalDate releaseDate = parseDate(episode.airDate());
        return new ProgressEpisodeDTO(
                seasonNumber,
                episode.episodeNumber(),
                episode.name(),
                releaseDate,
                episode.runtime(),
                episode.stillPath(),
                releaseDate == null || !releaseDate.isAfter(today));
    }

    private boolean isComplete(SeasonProgressDTO progress) {
        return progress.totalEpisodeCount() != null
                && progress.watchedEpisodeCount() != null
                && progress.watchedEpisodeCount() >= progress.totalEpisodeCount();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException ignored) {
            return null;
        }
    }
}
