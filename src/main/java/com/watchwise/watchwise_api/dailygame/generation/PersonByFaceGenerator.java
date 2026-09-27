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
                tmdbClient.getPopularMovies(DailyChallengeGenerationSupport.randomPage(),
                        TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        Optional<TmdbTvSearchResult> series = DailyChallengeGenerationSupport.randomItem(
                tmdbClient.getPopularSeries(DailyChallengeGenerationSupport.randomPage(),
                        TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        Map<Integer, PersonCandidate> people = new LinkedHashMap<>();
        movie.filter(item -> DailyChallengeGenerationSupport.validId(item.id()))
                .flatMap(item -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getMovieFullDetails(item.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .ifPresent(details -> addMoviePeople(people, details));
        series.filter(item -> DailyChallengeGenerationSupport.validId(item.id()))
                .flatMap(item -> DailyChallengeGenerationSupport.value(
                        tmdbClient.getTvFullDetails(item.id(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)))
                .ifPresent(details -> addSeriesPeople(people, details));

        return DailyChallengeGenerationSupport.randomItem(new ArrayList<>(people.values()), person ->
                        !excludedAnswerKeys.contains("PERSON:" + person.id()))
                .map(person -> snapshotAssembler.person(DailyGameType.PERSON_BY_FACE, String.valueOf(person.id()),
                        person.name(), TmdbImageUrlBuilder.profileUrl(person.profilePath()), null, List.of()));
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
