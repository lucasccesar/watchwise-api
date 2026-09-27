package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class ActorBySeriesFilmographyGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public ActorBySeriesFilmographyGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getTopRatedSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(series -> DailyChallengeGenerationSupport.validId(series.id()))
                .flatMap(series -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getTvFullDetails(series.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .flatMap(series -> eligibleActor(series).map(actor -> snapshotAssembler.actorFromSeries(series.id(), actor,
                        TmdbImageUrlBuilder.profileUrl(actor.profilePath()))));
    }

    private Optional<TmdbAggregateCastMember> eligibleActor(TmdbTvFullDetails series) {
        if (series.aggregateCredits() == null || series.aggregateCredits().cast() == null) {
            return Optional.empty();
        }
        List<TmdbAggregateCastMember> cast = series.aggregateCredits().cast().stream()
                .filter(actor -> actor != null && actor.id() != null && actor.name() != null && !actor.name().isBlank()
                        && DailyChallengeGenerationSupport.validImage(actor.profilePath()))
                .toList();
        return DailyChallengeGenerationSupport.randomItem(cast);
    }
}
