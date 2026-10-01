package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class MovieByInfoGenerator implements DailyChallengeGenerator {

    private static final String RATING_REGION = "BR";
    private static final List<String> ADDITIONAL_COMPARISON_FIELDS = List.of(
            "platforms", "genres", "certification", "director", "cast", "productionCompanies", "revenue");

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public MovieByInfoGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.MOVIE_BY_INFO;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        return DailyChallengeGenerationSupport.value(
                        tmdbClient.getPopularMovies(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .flatMap(page -> DailyChallengeGenerationSupport.randomItem(page.results(), movie ->
                        movie != null && DailyChallengeGenerationSupport.validId(movie.id())
                                && !excludedAnswerKeys.contains("MOVIE:" + movie.id())))
                .flatMap(movie -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getMovieFullDetails(movie.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .filter(this::hasRequiredMetadata)
                .flatMap(movie -> buildCandidate(movie));
    }

    private Optional<DailyChallengeCandidate> buildCandidate(TmdbMovieFullDetails movie) {
        String imagePath = TmdbImageUrlBuilder.posterUrl(movie.posterPath());
        String certification = DailyChallengeInfoSupport.movieCertification(
                        DailyChallengeGenerationSupport.value(tmdbClient.getMovieReleaseDates(movie.id(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)).orElse(null), RATING_REGION)
                .orElse(null);
        DailyChallengeCandidate candidate = snapshotAssembler.movieInfo(movie, imagePath, List.of(), certification);
        return hasComparableInfo(candidate.answerSnapshot()) ? Optional.of(candidate) : Optional.empty();
    }

    private boolean hasComparableInfo(Map<String, Object> answerSnapshot) {
        return answerSnapshot.get("year") instanceof Number
                && ADDITIONAL_COMPARISON_FIELDS.stream()
                .map(answerSnapshot::get)
                .anyMatch(this::isNonEmpty);
    }

    private boolean isNonEmpty(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String string) {
            return !string.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return true;
    }

    private boolean hasRequiredMetadata(TmdbMovieFullDetails movie) {
        return movie != null && DailyChallengeGenerationSupport.validId(movie.id())
                && movie.title() != null && !movie.title().isBlank()
                && DailyChallengeGenerationSupport.validImage(movie.posterPath());
    }

}
