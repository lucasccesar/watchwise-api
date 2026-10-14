package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;
import java.util.Optional;

@Component
public class MovieByPosterGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public MovieByPosterGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.MOVIE_BY_POSTER;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        return DailyChallengeGenerationSupport.value(
                        tmdbClient.getTopRatedMovies(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .map(page -> DailyChallengeGenerationSupport.shuffled(page.results()))
                .orElseGet(java.util.List::of).stream()
                .filter(movie -> movie != null && !excludedAnswerKeys.contains("MOVIE:" + movie.id())
                        && !DailyChallengeGenerationSupport.isAsianOriginalLanguage(movie.originalLanguage())
                        && isUsable(movie))
                .map(movie -> new java.util.AbstractMap.SimpleEntry<>(movie,
                        DailyChallengeGenerationSupport.value(tmdbClient.getMovieFullDetails(movie.id(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))))
                .filter(entry -> entry.getValue().filter(details -> movieHasCompletePresentation(entry.getKey(), details)).isPresent())
                .map(entry -> snapshotAssembler.moviePoster(entry.getKey(), entry.getValue().orElseThrow(),
                        TmdbImageUrlBuilder.posterUrl(entry.getKey().posterPath())))
                .findFirst();
    }

    private boolean movieHasCompletePresentation(TmdbMovieSearchResult movie,
                                                   TmdbMovieFullDetails details) {
        return movie.id().equals(details.id()) && details.title() != null && !details.title().isBlank()
                && DailyChallengeGenerationSupport.date(details.releaseDate()).isPresent()
                && details.genres() != null && !details.genres().isEmpty()
                && details.credits() != null && details.credits().crew() != null
                && details.credits().crew().stream().anyMatch(crew -> crew != null
                && "Director".equalsIgnoreCase(crew.job()) && crew.name() != null && !crew.name().isBlank());
    }

    private boolean isUsable(TmdbMovieSearchResult movie) {
        return DailyChallengeGenerationSupport.validId(movie.id())
                && movie.title() != null && !movie.title().isBlank()
                && DailyChallengeGenerationSupport.validImage(movie.posterPath());
    }
}
