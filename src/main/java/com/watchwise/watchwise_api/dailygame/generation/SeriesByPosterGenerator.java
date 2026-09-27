package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;
import java.util.Optional;

@Component
public class SeriesByPosterGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public SeriesByPosterGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.SERIES_BY_POSTER;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        return DailyChallengeGenerationSupport.value(
                        tmdbClient.getPopularSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .flatMap(page -> DailyChallengeGenerationSupport.randomItem(page.results(), series ->
                        series != null && !excludedAnswerKeys.contains("SERIES:" + series.id())))
                .filter(this::isUsable)
                .map(series -> snapshotAssembler.seriesPoster(series,
                        TmdbImageUrlBuilder.posterUrl(series.posterPath())));
    }

    private boolean isUsable(TmdbTvSearchResult series) {
        return DailyChallengeGenerationSupport.validId(series.id())
                && series.name() != null && !series.name().isBlank()
                && DailyChallengeGenerationSupport.validImage(series.posterPath());
    }
}
