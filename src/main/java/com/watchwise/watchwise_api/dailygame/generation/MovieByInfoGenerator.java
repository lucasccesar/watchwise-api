package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class MovieByInfoGenerator implements DailyChallengeGenerator {

    private static final String RATING_REGION = "BR";

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public MovieByInfoGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.MOVIE_BY_INFO;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getPopularMovies(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(movie -> DailyChallengeGenerationSupport.validId(movie.id()))
                .flatMap(movie -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getMovieFullDetails(movie.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .filter(this::hasRequiredMetadata)
                .flatMap(movie -> buildCandidate(movie));
    }

    private Optional<DailyChallengeCandidate> buildCandidate(TmdbMovieFullDetails movie) {
        String imagePath = TmdbImageUrlBuilder.posterUrl(movie.posterPath());
        List<DailyChallengeCandidate.HintSnapshot> hints = new ArrayList<>();
        addHint(hints, "PLATFORM", DailyChallengeInfoSupport.providers(movie.watchProviders(), RATING_REGION));
        addHint(hints, "GENRES", movie.genres() == null ? null : DailyChallengeGenerationSupport.joinNonBlank(
                movie.genres().stream().map(genre -> genre == null ? null : genre.name()).toList()));
        addHint(hints, "YEAR", DailyChallengeGenerationSupport.date(movie.releaseDate())
                .map(date -> String.valueOf(date.getYear())).orElse(null));
        DailyChallengeInfoSupport.movieCertification(
                        DailyChallengeGenerationSupport.value(tmdbClient.getMovieReleaseDates(movie.id(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)).orElse(null), RATING_REGION)
                .ifPresent(certification -> addHint(hints, "CERTIFICATION", certification));
        addHint(hints, "DIRECTOR", director(movie));
        addHint(hints, "CAST", movie.credits() == null || movie.credits().cast() == null ? null
                : DailyChallengeGenerationSupport.joinNonBlank(movie.credits().cast().stream()
                .map(cast -> cast == null ? null : cast.name()).toList()));
        addHint(hints, "PRODUCTION_COMPANIES", movie.productionCompanies() == null ? null
                : DailyChallengeGenerationSupport.joinNonBlank(movie.productionCompanies().stream()
                .map(company -> company == null ? null : company.name()).toList()));
        addHint(hints, "REVENUE", movie.revenue() == null || movie.revenue() <= 0 ? null
                : String.valueOf(movie.revenue()));
        if (hints.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshotAssembler.movieInfo(movie, imagePath, hints));
    }

    private boolean hasRequiredMetadata(TmdbMovieFullDetails movie) {
        return movie != null && DailyChallengeGenerationSupport.validId(movie.id())
                && movie.title() != null && !movie.title().isBlank()
                && DailyChallengeGenerationSupport.validImage(movie.posterPath());
    }

    private String director(TmdbMovieFullDetails movie) {
        if (movie.credits() == null || movie.credits().crew() == null) {
            return null;
        }
        return movie.credits().crew().stream()
                .filter(crew -> crew != null && crew.job() != null && "Director".equalsIgnoreCase(crew.job()))
                .map(TmdbCrewMember::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void addHint(List<DailyChallengeCandidate.HintSnapshot> hints, String type, String value) {
        if (value != null && !value.isBlank()) {
            hints.add(DailyChallengeSnapshotAssembler.hint(type, value));
        }
    }
}
