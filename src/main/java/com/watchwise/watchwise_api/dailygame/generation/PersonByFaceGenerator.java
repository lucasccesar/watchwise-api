package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class PersonByFaceGenerator implements DailyChallengeGenerator {

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public PersonByFaceGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.PERSON_BY_FACE;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        Optional<TmdbMovieSearchResult> movie = DailyChallengeGenerationSupport.randomItem(
                tmdbClient.getTopRatedMovies(DailyChallengeGenerationSupport.randomPage(),
                        TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE), item ->
                        item != null && !DailyChallengeGenerationSupport.isAsianOriginalLanguage(
                                item.originalLanguage()));
        Optional<TmdbTvSearchResult> series = DailyChallengeGenerationSupport.randomItem(
                tmdbClient.getTopRatedSeries(DailyChallengeGenerationSupport.randomPage(),
                        TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE), item ->
                        item != null && !DailyChallengeGenerationSupport.isAsianOriginalLanguage(
                                item.originalLanguage()));
        Map<Integer, PersonCandidate> people = new LinkedHashMap<>();
        movie.filter(item -> DailyChallengeGenerationSupport.validId(item.id()))
                .flatMap(item -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getMovieFullDetails(item.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .filter(details -> !DailyChallengeGenerationSupport.isAsianOriginalLanguage(
                        details.originalLanguage()))
                .ifPresent(details -> addMoviePeople(people, details));
        series.filter(item -> DailyChallengeGenerationSupport.validId(item.id()))
                .flatMap(item -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getTvFullDetails(item.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .filter(details -> !DailyChallengeGenerationSupport.isAsianOriginalLanguage(
                        details.originalLanguage()))
                .ifPresent(details -> addSeriesPeople(people, details));

        return DailyChallengeGenerationSupport.randomItem(new ArrayList<>(people.values()), person ->
                        !excludedAnswerKeys.contains("PERSON:" + person.id()))
                .flatMap(person -> DailyChallengeGenerationSupport.value(tmdbClient.getPersonAggregate(
                        String.valueOf(person.id()), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                        .filter(aggregate -> String.valueOf(person.id()).equals(aggregate.id()))
                        .map(aggregate -> snapshotAssembler.person(DailyGameType.PERSON_BY_FACE, String.valueOf(person.id()),
                                person.name(), TmdbImageUrlBuilder.profileUrl(person.profilePath()), person.profilePath(), null, List.of(),
                                aggregate.combinedCredits() == null ? List.of() : java.util.stream.Stream.concat(
                                        aggregate.combinedCredits().cast() == null ? java.util.stream.Stream.empty() : aggregate.combinedCredits().cast().stream(),
                                        aggregate.combinedCredits().crew() == null ? java.util.stream.Stream.empty() : aggregate.combinedCredits().crew().stream())
                                        .filter(credit -> credit != null)
                                        .map(credit -> credit.title() == null ? credit.name() : credit.title())
                                        .filter(title -> title != null && !title.isBlank()).distinct().limit(5).toList())));
    }

    private void addMoviePeople(Map<Integer, PersonCandidate> people, TmdbMovieFullDetails movie) {
        if (movie.credits() == null || movie.credits().cast() == null) {
            return;
        }
        movie.credits().cast().stream()
                .filter(person -> person.id() != null && person.name() != null && !person.name().isBlank()
                        && DailyChallengeGenerationSupport.validImage(person.profilePath()))
                .forEach(person -> people.putIfAbsent(person.id(),
                        new PersonCandidate(person.id(), person.name(), person.profilePath())));
    }

    private void addSeriesPeople(Map<Integer, PersonCandidate> people, TmdbTvFullDetails series) {
        if (series.aggregateCredits() == null || series.aggregateCredits().cast() == null) {
            return;
        }
        series.aggregateCredits().cast().stream()
                .filter(person -> person.id() != null && person.name() != null && !person.name().isBlank()
                        && DailyChallengeGenerationSupport.validImage(person.profilePath()))
                .forEach(person -> people.putIfAbsent(person.id(),
                        new PersonCandidate(person.id(), person.name(), person.profilePath())));
    }

    private record PersonCandidate(Integer id, String name, String profilePath) {
    }
}
