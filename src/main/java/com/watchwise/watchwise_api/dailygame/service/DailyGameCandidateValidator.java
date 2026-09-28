package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

@Component
public class DailyGameCandidateValidator {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    private final TmdbClient tmdbClient;

    public DailyGameCandidateValidator(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
    }

    public DailyGameCandidateIdentity validate(DailyGameType gameType, DailyGameAttemptRequest request) {
        if (gameType == null || request == null) {
            throw new BadRequestException("A daily game candidate is required");
        }
        return switch (gameType.targetKind()) {
            case MOVIE -> validateMovie(request);
            case SERIES -> validateSeries(request);
            case PERSON -> validatePerson(request);
            case EPISODE -> validateEpisode(request);
        };
    }

    private DailyGameCandidateIdentity validateMovie(DailyGameAttemptRequest request) {
        String tmdbId = onlyTmdbId(request);
        ensureFound(tmdbClient.getMovieFullDetails(tmdbId, LANGUAGE), "movie");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.MOVIE, tmdbId, null, null, null, null);
    }

    private DailyGameCandidateIdentity validateSeries(DailyGameAttemptRequest request) {
        String tmdbId = onlyTmdbId(request);
        ensureFound(tmdbClient.getTvFullDetails(tmdbId, LANGUAGE), "series");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.SERIES, tmdbId, null, null, null, null);
    }

    private DailyGameCandidateIdentity validatePerson(DailyGameAttemptRequest request) {
        if (request.tmdbId() != null || request.seriesTmdbId() != null
                || request.seasonNumber() != null || request.episodeNumber() != null) {
            throw invalidCandidate();
        }
        String personTmdbId = positiveIdentifier(request.personTmdbId());
        ensureFound(tmdbClient.getPersonDetails(personTmdbId), "person");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.PERSON, null, personTmdbId, null, null, null);
    }

    private DailyGameCandidateIdentity validateEpisode(DailyGameAttemptRequest request) {
        if (request.tmdbId() != null || request.personTmdbId() != null) {
            throw invalidCandidate();
        }
        String seriesTmdbId = positiveIdentifier(request.seriesTmdbId());
        Integer seasonNumber = positiveCoordinate(request.seasonNumber());
        Integer episodeNumber = positiveCoordinate(request.episodeNumber());
        ensureFound(tmdbClient.getEpisodeFullDetails(seriesTmdbId, seasonNumber, episodeNumber, LANGUAGE), "episode");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.EPISODE, null, null, seriesTmdbId,
                seasonNumber, episodeNumber);
    }

    private String onlyTmdbId(DailyGameAttemptRequest request) {
        if (request.personTmdbId() != null || request.seriesTmdbId() != null
                || request.seasonNumber() != null || request.episodeNumber() != null) {
            throw invalidCandidate();
        }
        return positiveIdentifier(request.tmdbId());
    }

    private String positiveIdentifier(String value) {
        if (value == null || !value.trim().matches("[1-9]\\d*")) {
            throw invalidCandidate();
        }
        return value.trim();
    }

    private Integer positiveCoordinate(Integer value) {
        if (value == null || value < 1) {
            throw invalidCandidate();
        }
        return value;
    }

    private void ensureFound(TmdbLookupResult<?> result, String target) {
        if (result == null || result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
        if (result.isNotFound()) {
            throw new BadRequestException("The selected " + target + " does not exist");
        }
        if (!(result instanceof TmdbLookupResult.Found<?>)) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
    }

    private BadRequestException invalidCandidate() {
        return new BadRequestException("Candidate fields do not match the daily game target");
    }
}
