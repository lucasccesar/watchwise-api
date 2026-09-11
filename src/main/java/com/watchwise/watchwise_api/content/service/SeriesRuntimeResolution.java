package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;

import java.util.List;

public record SeriesRuntimeResolution(
        SeriesRuntimeAggregate aggregate,
        List<TmdbSeasonFullDetails> seasonsFetchedForAggregate,
        boolean seasonFetchAttempted) {

    public SeriesRuntimeResolution(SeriesRuntimeAggregate aggregate, List<TmdbSeasonFullDetails> seasonsFetchedForAggregate) {
        this(aggregate, seasonsFetchedForAggregate, false);
    }
}
