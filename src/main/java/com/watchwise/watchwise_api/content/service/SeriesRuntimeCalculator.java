package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class SeriesRuntimeCalculator {

    public SeriesRuntimeAggregate calculate(
            List<TmdbSeasonFullDetails> seasons,
            List<TmdbSeasonSummary> seasonSummaries) {
        List<Integer> runtimes = regularSeasons(seasons)
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
                .filter(season -> season.seasonNumber() != null && season.seasonNumber() != 0);
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
