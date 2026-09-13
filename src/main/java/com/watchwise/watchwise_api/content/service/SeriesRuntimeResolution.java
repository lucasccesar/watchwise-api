package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupOrigin;

import java.util.List;
import java.util.Objects;

public record SeriesRuntimeResolution(
        SeriesRuntimeAggregate aggregate,
        List<TmdbSeasonFullDetails> seasonsFetchedForAggregate,
        boolean seasonFetchAttempted,
        List<LoadedSeason> seasonsFetchedWithOrigin) {

    public SeriesRuntimeResolution(SeriesRuntimeAggregate aggregate, List<TmdbSeasonFullDetails> seasonsFetchedForAggregate) {
        this(aggregate, seasonsFetchedForAggregate, false, remoteSeasons(seasonsFetchedForAggregate));
    }

    public SeriesRuntimeResolution(
            SeriesRuntimeAggregate aggregate,
            List<TmdbSeasonFullDetails> seasonsFetchedForAggregate,
            boolean seasonFetchAttempted) {
        this(aggregate, seasonsFetchedForAggregate, seasonFetchAttempted, remoteSeasons(seasonsFetchedForAggregate));
    }

    public SeriesRuntimeResolution {
        seasonsFetchedForAggregate = List.copyOf(seasonsFetchedForAggregate);
        seasonsFetchedWithOrigin = List.copyOf(seasonsFetchedWithOrigin);
    }

    private static List<LoadedSeason> remoteSeasons(List<TmdbSeasonFullDetails> seasons) {
        return seasons.stream()
                .map(season -> new LoadedSeason(season, TmdbLookupOrigin.REMOTE))
                .toList();
    }

    public record LoadedSeason(TmdbSeasonFullDetails details, TmdbLookupOrigin origin) {

        public LoadedSeason {
            Objects.requireNonNull(details, "season details are required");
            Objects.requireNonNull(origin, "season lookup origin is required");
        }
    }
}
