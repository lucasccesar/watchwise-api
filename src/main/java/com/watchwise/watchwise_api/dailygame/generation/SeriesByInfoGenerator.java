package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
        List<DailyChallengeCandidate.HintSnapshot> hints = new ArrayList<>();
        addHint(hints, "PLATFORM", DailyChallengeInfoSupport.providers(series.watchProviders(), RATING_REGION));
        addHint(hints, "GENRES", series.genres() == null ? null : DailyChallengeGenerationSupport.joinNonBlank(
                series.genres().stream().map(genre -> genre == null ? null : genre.name()).toList()));
        addHint(hints, "YEAR", DailyChallengeGenerationSupport.date(series.firstAirDate())
                .map(date -> String.valueOf(date.getYear())).orElse(null));
        String certification = DailyChallengeInfoSupport.tvCertification(
                        DailyChallengeGenerationSupport.value(tmdbClient.getTvContentRatings(series.id(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)).orElse(null), RATING_REGION)
                .orElse(null);
        addHint(hints, "CERTIFICATION", certification);
        addHint(hints, "CREATOR", series.createdBy() == null ? null : DailyChallengeGenerationSupport.joinNonBlank(
                series.createdBy().stream().map(creator -> creator == null ? null : creator.name()).toList()));
        addHint(hints, "CAST", series.aggregateCredits() == null || series.aggregateCredits().cast() == null ? null
                : DailyChallengeGenerationSupport.joinNonBlank(series.aggregateCredits().cast().stream()
                .map(cast -> cast == null ? null : cast.name()).toList()));
        addHint(hints, "NETWORKS_PRODUCTION_COMPANIES", networkAndCompanyNames(series));
        addHint(hints, "COUNTS", counts(series));
        if (hints.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshotAssembler.seriesInfo(series, imagePath, hints, certification));
    }

    private boolean hasRequiredMetadata(TmdbTvFullDetails series) {
        return series != null && DailyChallengeGenerationSupport.validId(series.id())
                && series.name() != null && !series.name().isBlank()
                && DailyChallengeGenerationSupport.validImage(series.posterPath());
    }

    private String networkAndCompanyNames(TmdbTvFullDetails series) {
        List<String> names = new ArrayList<>();
        if (series.networks() != null) {
            series.networks().stream().map(network -> network == null ? null : network.name()).forEach(names::add);
        }
        if (series.productionCompanies() != null) {
            series.productionCompanies().stream()
                    .map(company -> company == null ? null : company.name())
                    .forEach(names::add);
        }
        return DailyChallengeGenerationSupport.joinNonBlank(names);
    }

    private String counts(TmdbTvFullDetails series) {
        List<String> counts = new ArrayList<>();
        if (series.numberOfSeasons() != null && series.numberOfSeasons() > 0) {
            counts.add(series.numberOfSeasons() + " seasons");
        }
        if (series.numberOfEpisodes() != null && series.numberOfEpisodes() > 0) {
            counts.add(series.numberOfEpisodes() + " episodes");
        }
        return DailyChallengeGenerationSupport.joinNonBlank(counts);
    }

    private void addHint(List<DailyChallengeCandidate.HintSnapshot> hints, String type, String value) {
        if (value != null && !value.isBlank()) {
            hints.add(DailyChallengeSnapshotAssembler.hint(type, value));
        }
    }
}
