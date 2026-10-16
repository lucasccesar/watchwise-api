package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PersonAggregateReader {
    private final TmdbClient tmdbClient;

    public TmdbPersonAggregate read(String personTmdbId, String language) {
        validatePersonTmdbId(personTmdbId);
        TmdbLookupResult<TmdbPersonAggregate> lookup = tmdbClient.getPersonAggregate(personTmdbId, language);
        if (lookup instanceof TmdbLookupResult.Found<TmdbPersonAggregate> found) {
            if (found.value() != null) return found.value();
            throw new TmdbUnavailableException("TMDB returned an unusable person response");
        }
        if (lookup.isNotFound()) throw new NotFoundException("No person found on TMDB for the given id");
        throw new TmdbUnavailableException("TMDB is currently unavailable");
    }

    void validatePersonTmdbId(String personTmdbId) {
        if (personTmdbId == null || personTmdbId.length() > 20 || !personTmdbId.matches("\\d+")) {
            throw new BadRequestException("personTmdbId must be a numeric TMDB id up to 20 digits");
        }
    }
}
