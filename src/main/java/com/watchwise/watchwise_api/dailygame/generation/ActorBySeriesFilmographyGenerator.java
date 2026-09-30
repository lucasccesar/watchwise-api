package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
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
public class ActorBySeriesFilmographyGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;
    private final DailyGameFilmographyService filmographyService;

    public ActorBySeriesFilmographyGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this(tmdbClient, snapshotAssembler, new DailyGameFilmographyServiceImpl(tmdbClient));
    }

    @Autowired
    public ActorBySeriesFilmographyGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler,
                                             DailyGameFilmographyService filmographyService) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
        this.filmographyService = filmographyService;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getTopRatedSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(series -> series != null && DailyChallengeGenerationSupport.validId(series.id()))
                .flatMap(series -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getTvFullDetails(series.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .flatMap(series -> eligibleActor(series, excludedAnswerKeys)
                        .map(candidate -> snapshotAssembler.actorFromSeries(series.id(), candidate.actor(),
                                TmdbImageUrlBuilder.profileUrl(candidate.actor().profilePath()), candidate.snapshot().entries())));
    }

    private Optional<ActorCandidate<TmdbAggregateCastMember>> eligibleActor(
            TmdbTvFullDetails series, Set<String> excludedAnswerKeys) {
        if (series.aggregateCredits() == null || series.aggregateCredits().cast() == null) {
            return Optional.empty();
        }
        List<TmdbAggregateCastMember> cast = series.aggregateCredits().cast().stream()
                .filter(actor -> actor != null && actor.id() != null && actor.name() != null && !actor.name().isBlank()
                        && DailyChallengeGenerationSupport.validImage(actor.profilePath())
                        && !excludedAnswerKeys.contains("PERSON:" + actor.id()))
                .toList();
        List<ActorCandidate<TmdbAggregateCastMember>> eligible = cast.stream()
                .map(actor -> new ActorCandidate<>(actor, filmographyService.snapshot(
                        String.valueOf(actor.id()), gameType())))
                .filter(candidate -> candidate.snapshot().entries().size() >= 2)
                .toList();
        return DailyChallengeGenerationSupport.randomItem(eligible);
    }

    private record ActorCandidate<T>(T actor, DailyGameFilmographyService.FilmographySnapshot snapshot) {
    }
}
