package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeAggregate;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class SeriesProgressMetadataCalculator {

    private final SeriesRuntimeCalculator runtimeCalculator;

    public CalculatedSnapshot calculate(
            TmdbTvFullDetails tvDetails,
            List<TmdbSeasonFullDetails> loadedSeasons,
            LocalDate today) {
        return calculate(tvDetails == null ? null : tvDetails.id(), loadedSeasons, today);
    }

    public CalculatedSnapshot calculate(
            String seriesTmdbId,
            List<TmdbSeasonFullDetails> loadedSeasons,
            LocalDate today) {
        Map<Integer, TmdbSeasonFullDetails> seasonsByNumber = new LinkedHashMap<>();
        if (loadedSeasons != null) {
            loadedSeasons.stream()
                    .filter(Objects::nonNull)
                    .filter(season -> season.seasonNumber() != null && season.seasonNumber() > 0)
                    .sorted(Comparator.comparing(TmdbSeasonFullDetails::seasonNumber))
                    .forEach(season -> seasonsByNumber.putIfAbsent(season.seasonNumber(), season));
        }

        List<SeasonValues> seasons = seasonsByNumber.values().stream()
                .map(season -> calculateSeason(seriesTmdbId, season, today))
                .toList();
        SeriesRuntimeAggregate runtime = runtimeCalculator.calculateAired(
                seasonsByNumber.values().stream().toList(), today);
        int releasedEpisodeCount = seasons.stream()
                .mapToInt(SeasonValues::regularReleasedEpisodeCount)
                .sum();
        int knownRuntimeEpisodeCount = runtime.knownEpisodeCount() == null ? 0 : runtime.knownEpisodeCount();
        boolean complete = seasons.stream().allMatch(SeasonValues::runtimeComplete);
        Integer totalKnownRuntime = complete
                ? (runtime.totalRuntimeMinutes() == null ? 0 : runtime.totalRuntimeMinutes())
                : null;
        LocalDate lastReleasedEpisodeDate = seasons.stream()
                .map(SeasonValues::lastReleasedEpisodeDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        return new CalculatedSnapshot(
                new SeriesValues(
                        seriesTmdbId,
                        releasedEpisodeCount,
                        totalKnownRuntime,
                        knownRuntimeEpisodeCount,
                        lastReleasedEpisodeDate,
                        complete),
                seasons);
    }

    private SeasonValues calculateSeason(
            String seriesTmdbId,
            TmdbSeasonFullDetails season,
            LocalDate today) {
        List<TmdbEpisodeSummary> releasedEpisodes = runtimeCalculator.releasedEpisodes(season, today);
        SeriesRuntimeAggregate runtime = runtimeCalculator.calculateAired(List.of(season), today);
        int knownRuntimeEpisodeCount = runtime.knownEpisodeCount() == null ? 0 : runtime.knownEpisodeCount();
        boolean runtimeComplete = knownRuntimeEpisodeCount == releasedEpisodes.size();
        Integer totalKnownRuntime = runtimeComplete
                ? (runtime.totalRuntimeMinutes() == null ? 0 : runtime.totalRuntimeMinutes())
                : null;
        LocalDate lastReleasedEpisodeDate = releasedEpisodes.stream()
                .map(TmdbEpisodeSummary::airDate)
                .map(this::parseDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        return new SeasonValues(
                seriesTmdbId,
                season.seasonNumber(),
                releasedEpisodes.size(),
                totalKnownRuntime,
                knownRuntimeEpisodeCount,
                lastReleasedEpisodeDate,
                runtimeComplete);
    }

    private java.time.LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : java.time.LocalDate.parse(value);
        } catch (java.time.DateTimeException exception) {
            return null;
        }
    }

    public record CalculatedSnapshot(SeriesValues series, List<SeasonValues> seasons) {
        public CalculatedSnapshot {
            seasons = List.copyOf(seasons);
        }
    }

    public record SeriesValues(
            String seriesTmdbId,
            int regularReleasedEpisodeCount,
            Integer totalKnownRuntime,
            int knownRuntimeEpisodeCount,
            java.time.LocalDate lastReleasedEpisodeDate,
            boolean runtimeComplete) {
    }

    public record SeasonValues(
            String seriesTmdbId,
            int seasonNumber,
            int regularReleasedEpisodeCount,
            Integer totalKnownRuntime,
            int knownRuntimeEpisodeCount,
            java.time.LocalDate lastReleasedEpisodeDate,
            boolean runtimeComplete) {
    }
}
