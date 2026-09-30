package com.watchwise.watchwise_api.dailygame.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class DailyGameFilmographyLookupBudget {

    private final int maxSeriesDetailLookups;
    private final Map<String, Optional<Integer>> numberOfEpisodesBySeries = new HashMap<>();

    public DailyGameFilmographyLookupBudget(int maxSeriesDetailLookups) {
        if (maxSeriesDetailLookups < 1) {
            throw new IllegalArgumentException("maxSeriesDetailLookups must be positive");
        }
        this.maxSeriesDetailLookups = maxSeriesDetailLookups;
    }

    public Optional<Integer> getOrLoad(String seriesTmdbId, Function<String, Optional<Integer>> loader) {
        if (numberOfEpisodesBySeries.containsKey(seriesTmdbId)) {
            return numberOfEpisodesBySeries.get(seriesTmdbId);
        }
        if (numberOfEpisodesBySeries.size() >= maxSeriesDetailLookups) {
            return Optional.empty();
        }
        Optional<Integer> value = loader.apply(seriesTmdbId);
        Optional<Integer> cached = value == null ? Optional.empty() : value;
        numberOfEpisodesBySeries.put(seriesTmdbId, cached);
        return cached;
    }
}
