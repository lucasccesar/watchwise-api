package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class SeriesRuntimeCalculator {

    public SeriesRuntimeAggregate calculate(
            List<TmdbSeasonFullDetails> seasons,
            List<TmdbSeasonSummary> seasonSummaries) {
        return calculateKnownRuntimes(regularSeasons(seasons).toList(), seasonSummaries);
    }

    public SeriesRuntimeAggregate calculateAired(
            List<TmdbSeasonFullDetails> seasons,
            LocalDate cutoff) {
        List<TmdbSeasonFullDetails> airedSeasons = regularSeasons(seasons)
                .map(season -> withEpisodes(season, releasedEpisodes(season, cutoff)))
                .toList();
        return calculateKnownRuntimes(airedSeasons, List.of());
    }

    public List<TmdbEpisodeSummary> releasedEpisodes(
            TmdbSeasonFullDetails season,
            LocalDate cutoff) {
        if (season == null || season.episodes() == null || cutoff == null) {
            return List.of();
        }
        return season.episodes().stream()
                .filter(Objects::nonNull)
                .filter(episode -> episode.episodeNumber() != null && episode.episodeNumber() > 0)
                .filter(episode -> {
                    LocalDate airDate = parseDate(episode.airDate());
                    return airDate != null && !airDate.isAfter(cutoff);
                })
                .collect(java.util.stream.Collectors.toMap(
                        TmdbEpisodeSummary::episodeNumber,
                        episode -> episode,
                        (first, ignored) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
    }

    private SeriesRuntimeAggregate calculateKnownRuntimes(
            List<TmdbSeasonFullDetails> seasons,
            List<TmdbSeasonSummary> seasonSummaries) {
        List<Integer> runtimes = seasons.stream()
                .filter(Objects::nonNull)
                .flatMap(season -> season.episodes() == null
                        ? Stream.empty()
                        : season.episodes().stream()
                                .filter(Objects::nonNull)
                                .map(TmdbEpisodeSummary::runtime)
                                .filter(Objects::nonNull))
                .toList();

        Integer total = runtimes.isEmpty()
                ? null
                : runtimes.stream().mapToInt(Integer::intValue).sum();
        Integer average = total == null
                ? null
                : (int) Math.round(total / (double) runtimes.size());
        Integer knownEpisodeCount = runtimes.isEmpty() ? null : runtimes.size();

        return new SeriesRuntimeAggregate(
                total,
                average,
                knownEpisodeCount,
                reportedEpisodeCount(seasonSummaries));
    }

    private Stream<TmdbSeasonFullDetails> regularSeasons(List<TmdbSeasonFullDetails> seasons) {
        if (seasons == null) {
            return Stream.empty();
        }
        return seasons.stream()
                .filter(Objects::nonNull)
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0);
    }

    private TmdbSeasonFullDetails withEpisodes(
            TmdbSeasonFullDetails season,
            List<TmdbEpisodeSummary> episodes) {
        return new TmdbSeasonFullDetails(
                season.id(), season.name(), season.overview(), season.posterPath(), season.airDate(),
                season.seasonNumber(), episodes, season.aggregateCredits(), season.watchProviders());
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private Integer reportedEpisodeCount(List<TmdbSeasonSummary> seasonSummaries) {
        if (seasonSummaries == null || seasonSummaries.isEmpty()) {
            return null;
        }

        List<TmdbSeasonSummary> regularSeasons = seasonSummaries.stream()
                .filter(Objects::nonNull)
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() != 0)
                .toList();
        if (regularSeasons.isEmpty() || regularSeasons.stream().anyMatch(season -> season.episodeCount() == null)) {
            return null;
        }
        return regularSeasons.stream()
                .mapToInt(TmdbSeasonSummary::episodeCount)
                .sum();
    }
}
