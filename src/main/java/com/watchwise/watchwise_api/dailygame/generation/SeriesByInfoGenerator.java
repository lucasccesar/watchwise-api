package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class SeriesByInfoGenerator implements DailyChallengeGenerator {

    private static final String RATING_REGION = "BR";
    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public SeriesByInfoGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.SERIES_BY_INFO;
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
                        series != null && DailyChallengeGenerationSupport.validId(series.id())
                                && !excludedAnswerKeys.contains("SERIES:" + series.id())))
                .flatMap(series -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getTvFullDetails(series.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .filter(this::hasRequiredMetadata)
                .flatMap(this::buildCandidate);
    }

    private Optional<DailyChallengeCandidate> buildCandidate(TmdbTvFullDetails series) {
        String imagePath = TmdbImageUrlBuilder.posterUrl(series.posterPath());
        String certification = DailyChallengeInfoSupport.tvCertification(
                        DailyChallengeGenerationSupport.value(tmdbClient.getTvContentRatings(series.id(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)).orElse(null), RATING_REGION)
                .orElse(null);
        DailyChallengeCandidate candidate = snapshotAssembler.seriesInfo(series, imagePath, List.of(), certification);
        return hasComparableInfo(candidate.answerSnapshot()) ? Optional.of(candidate) : Optional.empty();
    }

    private boolean hasComparableInfo(Map<String, Object> answerSnapshot) {
        return DailyChallengeInfoSupport.hasComparableInfo(
                answerSnapshot, DailyChallengeInfoSupport.SERIES_COMPARABLE_FIELDS);
    }

    private boolean hasRequiredMetadata(TmdbTvFullDetails series) {
        return series != null && DailyChallengeGenerationSupport.validId(series.id())
                && series.name() != null && !series.name().isBlank()
                && DailyChallengeGenerationSupport.validImage(series.posterPath());
    }

}
