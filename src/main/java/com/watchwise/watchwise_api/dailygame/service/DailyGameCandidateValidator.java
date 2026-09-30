package com.watchwise.watchwise_api.dailygame.service;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

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
        TmdbMovieFullDetails movie = ensureFound(
                tmdbClient.getMovieFullDetails(tmdbId, LANGUAGE), "movie");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.MOVIE, tmdbId, null, null, null, null,
                movie.title(), TmdbImageUrlBuilder.posterUrl(movie.posterPath()), parseDate(movie.releaseDate()));
    }

    private DailyGameCandidateIdentity validateSeries(DailyGameAttemptRequest request) {
        String tmdbId = onlyTmdbId(request);
        TmdbTvFullDetails series = ensureFound(
                tmdbClient.getTvFullDetails(tmdbId, LANGUAGE), "series");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.SERIES, tmdbId, null, null, null, null,
                series.name(), TmdbImageUrlBuilder.posterUrl(series.posterPath()), parseDate(series.firstAirDate()));
    }

    private DailyGameCandidateIdentity validatePerson(DailyGameAttemptRequest request) {
        if (request.tmdbId() != null || request.seriesTmdbId() != null
                || request.seasonNumber() != null || request.episodeNumber() != null) {
            throw invalidCandidate();
        }
        String personTmdbId = positiveIdentifier(request.personTmdbId());
        TmdbPersonDetails person = ensureFound(tmdbClient.getPersonDetails(personTmdbId), "person");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.PERSON, null, personTmdbId, null, null, null,
                person.name(), TmdbImageUrlBuilder.profileUrl(person.profilePath()), parseDate(person.birthday()));
    }

    private DailyGameCandidateIdentity validateEpisode(DailyGameAttemptRequest request) {
        if (request.tmdbId() != null || request.personTmdbId() != null) {
            throw invalidCandidate();
        }
        String seriesTmdbId = positiveIdentifier(request.seriesTmdbId());
        Integer seasonNumber = positiveCoordinate(request.seasonNumber());
        Integer episodeNumber = positiveCoordinate(request.episodeNumber());
        TmdbEpisodeFullDetails episode = ensureFound(
                tmdbClient.getEpisodeFullDetails(seriesTmdbId, seasonNumber, episodeNumber, LANGUAGE), "episode");
        return new DailyGameCandidateIdentity(DailyGameTargetKind.EPISODE, null, null, seriesTmdbId,
                seasonNumber, episodeNumber, episode.name(), TmdbImageUrlBuilder.stillUrl(episode.stillPath()),
                parseDate(episode.airDate()));
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

    private <T> T ensureFound(TmdbLookupResult<T> result, String target) {
        if (result == null || result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
        if (result.isNotFound()) {
            throw new BadRequestException("The selected " + target + " does not exist");
        }
        if (!(result instanceof TmdbLookupResult.Found<T> found) || found.value() == null) {
            throw new TmdbUnavailableException("TMDB is temporarily unavailable");
        }
        return found.value();
    }

    private BadRequestException invalidCandidate() {
        return new BadRequestException("Candidate fields do not match the daily game target");
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
