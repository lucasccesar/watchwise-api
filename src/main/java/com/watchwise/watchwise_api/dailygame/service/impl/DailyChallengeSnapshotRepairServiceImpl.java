package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeCandidate;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeInfoSupport;
import com.watchwise.watchwise_api.dailygame.generation.DailyChallengeSnapshotAssembler;
import com.watchwise.watchwise_api.dailygame.repository.DailyChallengeRepository;
import com.watchwise.watchwise_api.dailygame.service.DailyChallengeSnapshotRepairService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DailyChallengeSnapshotRepairServiceImpl implements DailyChallengeSnapshotRepairService {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
    private static final String RATING_REGION = "BR";

    private final DailyChallengeRepository challengeRepository;
    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;
    private final DailyGameFilmographyService filmographyService;

    public DailyChallengeSnapshotRepairServiceImpl(
            DailyChallengeRepository challengeRepository,
            TmdbClient tmdbClient,
            DailyChallengeSnapshotAssembler snapshotAssembler,
            DailyGameFilmographyService filmographyService) {
        this.challengeRepository = challengeRepository;
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
        this.filmographyService = filmographyService;
    }

    @Override
    public boolean repairIfIncomplete(DailyChallenge challenge) {
        if (challenge == null || challenge.getGameType() == null) {
            return false;
        }
        return switch (challenge.getGameType()) {
            case MOVIE_BY_INFO -> repairMovieInformation(challenge);
            case SERIES_BY_INFO -> repairSeriesInformation(challenge);
            case ACTOR_BY_MOVIE_FILMOGRAPHY, ACTOR_BY_SERIES_FILMOGRAPHY -> repairFilmography(challenge);
            default -> false;
        };
    }

    private boolean repairMovieInformation(DailyChallenge challenge) {
        if (DailyChallengeInfoSupport.hasComparableInfo(
                challenge.getAnswerSnapshot(), DailyChallengeInfoSupport.MOVIE_COMPARABLE_FIELDS)) {
            return false;
        }
        if (!usable(challenge.getTargetTmdbId()) || !usable(challenge.getImagePath())) {
            return false;
        }
        TmdbMovieFullDetails movie = found(tmdbClient.getMovieFullDetails(challenge.getTargetTmdbId(), LANGUAGE));
        if (!validMovieMetadata(movie, challenge.getTargetTmdbId())) {
            return false;
        }
        TmdbMovieReleaseDates releaseDates = found(
                tmdbClient.getMovieReleaseDates(challenge.getTargetTmdbId(), LANGUAGE));
        if (releaseDates == null) {
            return false;
        }
        String certification = movieCertification(releaseDates);
        DailyChallengeCandidate candidate = snapshotAssembler.movieInfo(
                movie, challenge.getImagePath(), List.of(), certification);
        if (!DailyChallengeInfoSupport.hasComparableInfo(
                candidate.answerSnapshot(), DailyChallengeInfoSupport.MOVIE_COMPARABLE_FIELDS)) {
            return false;
        }
        return mergeAndSave(challenge, candidate.answerSnapshot());
    }

    private boolean repairSeriesInformation(DailyChallenge challenge) {
        if (DailyChallengeInfoSupport.hasComparableInfo(
                challenge.getAnswerSnapshot(), DailyChallengeInfoSupport.SERIES_COMPARABLE_FIELDS)) {
            return false;
        }
        if (!usable(challenge.getTargetTmdbId()) || !usable(challenge.getImagePath())) {
            return false;
        }
        TmdbTvFullDetails series = found(tmdbClient.getTvFullDetails(challenge.getTargetTmdbId(), LANGUAGE));
        if (!validSeriesMetadata(series, challenge.getTargetTmdbId())) {
            return false;
        }
        TmdbTvContentRatings ratings = found(
                tmdbClient.getTvContentRatings(challenge.getTargetTmdbId(), LANGUAGE));
        if (ratings == null) {
            return false;
        }
        String certification = tvCertification(ratings);
        DailyChallengeCandidate candidate = snapshotAssembler.seriesInfo(
                series, challenge.getImagePath(), List.of(), certification);
        if (!DailyChallengeInfoSupport.hasComparableInfo(
                candidate.answerSnapshot(), DailyChallengeInfoSupport.SERIES_COMPARABLE_FIELDS)) {
            return false;
        }
        return mergeAndSave(challenge, candidate.answerSnapshot());
    }

    private boolean repairFilmography(DailyChallenge challenge) {
        Map<String, Object> existingSnapshot = challenge.getAnswerSnapshot();
        if (hasCompleteFilmography(existingSnapshot)) {
            return false;
        }
        if (!usable(challenge.getTargetTmdbId())) {
            return false;
        }
        DailyGameFilmographyService.FilmographySnapshot snapshot = filmographyService.snapshot(
                challenge.getTargetTmdbId(), challenge.getGameType());
        if (snapshot == null || snapshot.entries().isEmpty()) {
            return false;
        }
        return mergeAndSave(challenge, Map.of("filmography", snapshot.entries()));
    }

    private boolean mergeAndSave(DailyChallenge challenge, Map<String, Object> candidateSnapshot) {
        Map<String, Object> repairedSnapshot = new LinkedHashMap<>();
        if (challenge.getAnswerSnapshot() != null) {
            repairedSnapshot.putAll(challenge.getAnswerSnapshot());
        }
        boolean changed = false;
        for (Map.Entry<String, Object> entry : candidateSnapshot.entrySet()) {
            if (isMissingOrInvalid(repairedSnapshot.get(entry.getKey()), entry.getKey())) {
                repairedSnapshot.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        challenge.setAnswerSnapshot(repairedSnapshot);
        challengeRepository.saveAndFlush(challenge);
        return true;
    }

    private <T> T found(TmdbLookupResult<T> lookup) {
        if (lookup instanceof TmdbLookupResult.Found<T> found && found.value() != null) {
            return found.value();
        }
        return null;
    }

    private boolean validMovieMetadata(TmdbMovieFullDetails movie, String targetTmdbId) {
        return movie != null && validId(targetTmdbId) && targetTmdbId.equals(movie.id())
                && nonBlank(movie.title());
    }

    private boolean validSeriesMetadata(TmdbTvFullDetails series, String targetTmdbId) {
        return series != null && validId(targetTmdbId) && targetTmdbId.equals(series.id())
                && nonBlank(series.name());
    }

    private boolean validId(String value) {
        return value != null && value.matches("[1-9]\\d*");
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String movieCertification(TmdbMovieReleaseDates releaseDates) {
        if (releaseDates.results() == null) {
            return null;
        }
        return releaseDates.results().stream()
                .filter(region -> region != null && RATING_REGION.equals(region.isoCode()))
                .map(TmdbRegionReleaseDates::releaseDates)
                .filter(dates -> dates != null)
                .flatMap(Collection::stream)
                .filter(release -> release != null)
                .map(TmdbMovieReleaseDate::certification)
                .filter(this::usable)
                .findFirst()
                .orElse(null);
    }

    private String tvCertification(TmdbTvContentRatings ratings) {
        if (ratings.results() == null) {
            return null;
        }
        return ratings.results().stream()
                .filter(rating -> rating != null && RATING_REGION.equals(rating.isoCode()))
                .map(TmdbTvContentRating::rating)
                .filter(this::usable)
                .findFirst()
                .orElse(null);
    }

    private boolean usable(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String string) {
            return !string.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private boolean hasCompleteFilmography(Map<String, Object> snapshot) {
        return snapshot != null && snapshot.get("filmography") instanceof List<?> entries && !entries.isEmpty();
    }

    private boolean isMissingOrInvalid(Object value, String key) {
        return "filmography".equals(key) ? !(value instanceof List<?> entries && !entries.isEmpty()) : !usable(value);
    }
}
