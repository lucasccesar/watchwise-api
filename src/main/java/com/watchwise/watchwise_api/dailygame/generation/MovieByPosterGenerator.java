package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
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
                        tmdbClient.getPopularMovies(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .flatMap(page -> DailyChallengeGenerationSupport.randomItem(page.results(), movie ->
                        movie != null && !excludedAnswerKeys.contains("MOVIE:" + movie.id())))
                .filter(this::isUsable)
                .map(movie -> snapshotAssembler.moviePoster(movie, TmdbImageUrlBuilder.posterUrl(movie.posterPath())));
    }

    private boolean isUsable(TmdbMovieSearchResult movie) {
        return DailyChallengeGenerationSupport.validId(movie.id())
                && movie.title() != null && !movie.title().isBlank()
                && DailyChallengeGenerationSupport.validImage(movie.posterPath());
    }
}
