package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.content.service.SeriesRuntimeCalculator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

@Component
public class EpisodeRatingsReleasedEpisodes {

    private final TmdbClient tmdbClient;
    private final SeriesRuntimeCalculator runtimeCalculator;
    private final ExecutorService seasonFetchExecutor;

    public EpisodeRatingsReleasedEpisodes(TmdbClient tmdbClient, SeriesRuntimeCalculator runtimeCalculator,
            @Qualifier("tmdbSeasonFetchExecutor") ExecutorService seasonFetchExecutor) {
        this.tmdbClient = tmdbClient;
        this.runtimeCalculator = runtimeCalculator;
        this.seasonFetchExecutor = seasonFetchExecutor;
    }

    public Map<Integer, List<Integer>> load(String seriesTmdbId, List<Integer> regularSeasonNumbers, LocalDate today) {
        Map<Integer, CompletableFuture<List<Integer>>> futures = new LinkedHashMap<>();
        for (Integer seasonNumber : regularSeasonNumbers) {
            futures.computeIfAbsent(seasonNumber, number -> CompletableFuture.supplyAsync(
                    () -> loadSeason(seriesTmdbId, number, today), seasonFetchExecutor));
        }
        Map<Integer, List<Integer>> episodes = new LinkedHashMap<>();
        for (Map.Entry<Integer, CompletableFuture<List<Integer>>> entry : futures.entrySet()) {
            try {
                episodes.put(entry.getKey(), entry.getValue().join());
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw exception;
            }
        }
        return episodes;
    }

    private List<Integer> loadSeason(String seriesTmdbId, Integer seasonNumber, LocalDate today) {
        TmdbLookupResult<TmdbSeasonFullDetails> lookup = tmdbClient.getSeasonFullDetails(
                seriesTmdbId, seasonNumber, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbSeasonFullDetails> found)
                || found.value().episodes() == null
                || !seasonNumber.equals(found.value().seasonNumber())) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        return runtimeCalculator.releasedEpisodes(found.value(), today).stream()
                .map(episode -> episode.episodeNumber())
                .toList();
    }
}
