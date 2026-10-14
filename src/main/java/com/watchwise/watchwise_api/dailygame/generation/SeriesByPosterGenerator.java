package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
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
                        tmdbClient.getTopRatedSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .map(page -> DailyChallengeGenerationSupport.shuffled(page.results()))
                .orElseGet(java.util.List::of).stream()
                .filter(series -> series != null && !excludedAnswerKeys.contains("SERIES:" + series.id())
                        && !DailyChallengeGenerationSupport.isAsianOriginalLanguage(series.originalLanguage())
                        && isUsable(series))
                .map(series -> new java.util.AbstractMap.SimpleEntry<>(series,
                        DailyChallengeGenerationSupport.value(tmdbClient.getTvFullDetails(series.id(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))))
                .filter(entry -> entry.getValue().filter(details -> seriesHasCompletePresentation(entry.getKey(), details)).isPresent())
                .map(entry -> snapshotAssembler.seriesPoster(entry.getKey(), entry.getValue().orElseThrow(),
                        TmdbImageUrlBuilder.posterUrl(entry.getKey().posterPath())))
                .findFirst();
    }

    private boolean seriesHasCompletePresentation(TmdbTvSearchResult series,
                                                    TmdbTvFullDetails details) {
        return series.id().equals(details.id()) && details.name() != null && !details.name().isBlank()
                && DailyChallengeGenerationSupport.date(details.firstAirDate()).isPresent()
                && details.genres() != null && !details.genres().isEmpty()
                && details.createdBy() != null && !details.createdBy().isEmpty()
                && details.createdBy().stream().anyMatch(creator -> creator != null
                && creator.name() != null && !creator.name().isBlank())
                && details.numberOfSeasons() != null && details.numberOfSeasons() > 0;
    }

    private boolean isUsable(TmdbTvSearchResult series) {
        return DailyChallengeGenerationSupport.validId(series.id())
                && series.name() != null && !series.name().isBlank()
                && DailyChallengeGenerationSupport.validImage(series.posterPath());
    }
}
