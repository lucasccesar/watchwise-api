package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyLookupBudget;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
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
        DailyGameFilmographyLookupBudget budget = filmographyService.newGenerationBudget();
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getTopRatedSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(series -> series != null && DailyChallengeGenerationSupport.validId(series.id())
                        && !DailyChallengeGenerationSupport.isAsianOriginalLanguage(series.originalLanguage()))
                .flatMap(series -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getTvFullDetails(series.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .filter(series -> !DailyChallengeGenerationSupport.isAsianOriginalLanguage(series.originalLanguage()))
                .flatMap(series -> eligibleActor(series, excludedAnswerKeys, budget)
                        .map(candidate -> snapshotAssembler.actorFromSeries(series.id(), candidate.actor(),
                                TmdbImageUrlBuilder.profileUrl(candidate.actor().profilePath()), candidate.snapshot().entries())));
    }

    private Optional<ActorCandidate<TmdbAggregateCastMember>> eligibleActor(
            TmdbTvFullDetails series,
            Set<String> excludedAnswerKeys,
            DailyGameFilmographyLookupBudget budget) {
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
                        String.valueOf(actor.id()), gameType(), budget)))
                .filter(candidate -> filmographyService.hasAtLeastEntries(
                        candidate.snapshot(), gameType(), true, 2))
                .toList();
        return DailyChallengeGenerationSupport.randomItem(eligible.stream().limit(4).toList());
    }

    private record ActorCandidate<T>(T actor, DailyGameFilmographyService.FilmographySnapshot snapshot) {
    }
}
