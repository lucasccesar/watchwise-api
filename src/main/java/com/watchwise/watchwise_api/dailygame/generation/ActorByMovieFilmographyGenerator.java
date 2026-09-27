package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class ActorByMovieFilmographyGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public ActorByMovieFilmographyGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getTopRatedMovies(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(movie -> DailyChallengeGenerationSupport.validId(movie.id()))
                .flatMap(movie -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getMovieFullDetails(movie.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .flatMap(movie -> eligibleActor(movie).map(actor -> snapshotAssembler.actorFromMovie(movie.id(), actor,
                        TmdbImageUrlBuilder.profileUrl(actor.profilePath()))));
    }

    private Optional<TmdbCastMember> eligibleActor(TmdbMovieFullDetails movie) {
        if (movie.credits() == null || movie.credits().cast() == null) {
            return Optional.empty();
        }
        List<TmdbCastMember> cast = movie.credits().cast().stream()
                .filter(actor -> actor != null && actor.id() != null && actor.name() != null && !actor.name().isBlank()
                        && DailyChallengeGenerationSupport.validImage(actor.profilePath()))
                .toList();
        return DailyChallengeGenerationSupport.randomItem(cast);
    }
}
