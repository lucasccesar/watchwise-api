package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
import com.watchwise.watchwise_api.dailygame.service.impl.DailyGameFilmographyServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ActorByMovieFilmographyGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;
    private final DailyGameFilmographyService filmographyService;

    public ActorByMovieFilmographyGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this(tmdbClient, snapshotAssembler, new DailyGameFilmographyServiceImpl(tmdbClient));
    }

    @Autowired
    public ActorByMovieFilmographyGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler,
                                            DailyGameFilmographyService filmographyService) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
        this.filmographyService = filmographyService;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getTopRatedMovies(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(movie -> movie != null && DailyChallengeGenerationSupport.validId(movie.id()))
                .flatMap(movie -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getMovieFullDetails(movie.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .flatMap(movie -> eligibleActor(movie, excludedAnswerKeys)
                        .map(candidate -> snapshotAssembler.actorFromMovie(movie.id(), candidate.actor(),
                                TmdbImageUrlBuilder.profileUrl(candidate.actor().profilePath()), candidate.snapshot().entries())));
    }

    private Optional<ActorCandidate<TmdbCastMember>> eligibleActor(
            TmdbMovieFullDetails movie, Set<String> excludedAnswerKeys) {
        if (movie.credits() == null || movie.credits().cast() == null) {
            return Optional.empty();
        }
        List<TmdbCastMember> cast = movie.credits().cast().stream()
                .filter(actor -> actor != null && actor.id() != null && actor.name() != null && !actor.name().isBlank()
                        && DailyChallengeGenerationSupport.validImage(actor.profilePath())
                        && !excludedAnswerKeys.contains("PERSON:" + actor.id()))
                .toList();
        List<ActorCandidate<TmdbCastMember>> eligible = cast.stream()
                .map(actor -> new ActorCandidate<>(actor, filmographyService.snapshot(
                        String.valueOf(actor.id()), gameType())))
                .filter(candidate -> candidate.snapshot().entries().size() >= 2)
                .toList();
        return DailyChallengeGenerationSupport.randomItem(eligible);
    }

    private record ActorCandidate<T>(T actor, DailyGameFilmographyService.FilmographySnapshot snapshot) {
    }
}
