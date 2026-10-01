package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonDirection;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameComparisonStatus;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameInfoFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameCandidateIdentity;
import com.watchwise.watchwise_api.dailygame.service.DailyGameInfoComparisonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyGameInfoComparisonServiceTest {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;

    @Mock
    private TmdbClient tmdbClient;

    @Test
    @DisplayName("[compare] Should Apply Exact Partial And No Match Set Statuses - When Info Sets Overlap")
    void shouldApplyExactPartialAndNoMatchSetStatusesWhenInfoSetsOverlap() {
        DailyChallenge challenge = movieChallenge(snapshot(
                "platforms", List.of("Netflix", "Prime Video"),
                "genres", List.of("Drama", "Thriller"),
                "productionCompanies", List.of("Warner Bros.")));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, 50L,
                List.of(new TmdbGenre(18, "DRAMA"), new TmdbGenre(53, "Comedy")),
                List.of("Prime Video", "Netflix"), List.of(), List.of(),
                List.of(new TmdbProductionCompany(1, "A24", null, "US")));
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.platforms().status()).isEqualTo(DailyGameComparisonStatus.MATCH);
        assertThat(feedback.platforms().matchedValues()).containsExactly("Prime Video", "Netflix");
        assertThat(feedback.genres().status()).isEqualTo(DailyGameComparisonStatus.PARTIAL);
        assertThat(feedback.genres().matchedValues()).containsExactly("DRAMA");
        assertThat(feedback.productionCompanies().status()).isEqualTo(DailyGameComparisonStatus.NO_MATCH);
    }

    @Test
    @DisplayName("[compare] Should Compare Certification And Director Case Insensitively - When Movie Metadata Matches")
    void shouldCompareCertificationAndDirectorCaseInsensitivelyWhenMovieMetadataMatches() {
        DailyChallenge challenge = movieChallenge(snapshot(
                "certification", "18",
                "director", "David Fincher"));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, 50L,
                List.of(), List.of(), List.of(),
                List.of(new TmdbCrewMember(1, " david fincher ", "Director", null)), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));
        when(tmdbClient.getMovieReleaseDates("680", LANGUAGE)).thenReturn(found(new TmdbMovieReleaseDates("680",
                List.of(new TmdbRegionReleaseDates("BR", List.of(
                        new TmdbMovieReleaseDate("18", null, null, null, 3)))))));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.certification().status()).isEqualTo(DailyGameComparisonStatus.MATCH);
        assertThat(feedback.director().status()).isEqualTo(DailyGameComparisonStatus.MATCH);
        assertThat(feedback.certification().displayValue()).isEqualTo("18");
        assertThat(feedback.director().displayValue()).isEqualTo("david fincher");
    }

    @Test
    @DisplayName("[compare] Should Mark Creator Intersection As Partial - When Not Every Creator Matches")
    void shouldMarkCreatorIntersectionAsPartialWhenNotEveryCreatorMatches() {
        DailyChallenge challenge = seriesChallenge(snapshot(
                "creators", List.of("Vince Gilligan", "Peter Gould")));
        TmdbTvFullDetails candidate = series("1396", 5, List.of("vINCE gilligan"),
                List.of(), List.of(), List.of());
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, seriesIdentity("1396"));

        assertThat(feedback.creators().status()).isEqualTo(DailyGameComparisonStatus.PARTIAL);
        assertThat(feedback.creators().matchedValues()).containsExactly("vINCE gilligan");
        assertThat(feedback.creators().matchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[compare] Should Mark Complete Creator Set As Match - When All Creators Match")
    void shouldMarkCompleteCreatorSetAsMatchWhenAllCreatorsMatch() {
        DailyChallenge challenge = seriesChallenge(snapshot("creators", List.of("Vince Gilligan", "Peter Gould")));
        TmdbTvFullDetails candidate = series("1396", 5, List.of("peter gould", "vince gilligan"),
                List.of(), List.of(), List.of());
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, seriesIdentity("1396"));

        assertThat(feedback.creators().status()).isEqualTo(DailyGameComparisonStatus.MATCH);
        assertThat(feedback.creators().matchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[compare] Should Mark One Or Two Shared Cast Members As Partial - When Cast Is Below Three")
    void shouldMarkOneOrTwoSharedCastMembersAsPartialWhenCastIsBelowThree() {
        DailyChallenge challenge = movieChallenge(snapshot("cast", List.of("A", "B", "C")));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, 50L,
                List.of(), List.of(), List.of("B", "C"), List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.cast().status()).isEqualTo(DailyGameComparisonStatus.PARTIAL);
        assertThat(feedback.cast().matchedValues()).containsExactly("B", "C");
        assertThat(feedback.cast().matchCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[compare] Should Mark Three Shared Cast Members As Match - When Cast Reaches The Threshold")
    void shouldMarkThreeSharedCastMembersAsMatchWhenCastReachesTheThreshold() {
        DailyChallenge challenge = movieChallenge(snapshot("cast", List.of("A", "B", "C", "D")));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, 50L,
                List.of(), List.of(), List.of("C", "A", "B"), List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.cast().status()).isEqualTo(DailyGameComparisonStatus.MATCH);
        assertThat(feedback.cast().matchedValues()).containsExactly("C", "A", "B");
    }

    @Test
    @DisplayName("[compare] Should Limit Candidate Cast Feedback To Ten Names")
    void shouldLimitCandidateCastFeedbackToTenNames() {
        List<String> candidateCast = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "Actor " + index)
                .toList();
        DailyChallenge challenge = movieChallenge(snapshot("cast", List.of("Actor 1")));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, 50L,
                List.of(), List.of(), candidateCast, List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        @SuppressWarnings("unchecked")
        List<String> returnedCast = (List<String>) feedback.cast().displayValue();
        assertThat(returnedCast).containsExactlyElementsOf(candidateCast.subList(0, 10));
    }

    @Test
    @DisplayName("[compare] Should Use Secret Direction And One Year Partial Threshold - When Years Differ")
    void shouldUseSecretDirectionAndOneYearPartialThresholdWhenYearsDiffer() {
        DailyChallenge challenge = movieChallenge(snapshot("year", 2000));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 2001, 50L,
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.year().status()).isEqualTo(DailyGameComparisonStatus.PARTIAL);
        assertThat(feedback.year().direction()).isEqualTo(DailyGameComparisonDirection.SECRET_LOWER);
        assertThat(feedback.year().displayValue()).isEqualTo(2001);
    }

    @Test
    @DisplayName("[compare] Should Mark Year As No Match - When Difference Exceeds One Year")
    void shouldMarkYearAsNoMatchWhenDifferenceExceedsOneYear() {
        DailyChallenge challenge = movieChallenge(snapshot("year", 2000));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 2002, 50L,
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.year().status()).isEqualTo(DailyGameComparisonStatus.NO_MATCH);
        assertThat(feedback.year().direction()).isEqualTo(DailyGameComparisonDirection.SECRET_LOWER);
    }

    @Test
    @DisplayName("[compare] Should Mark Revenue At Ten Percent As Match - When Relative Difference Uses The Larger Value")
    void shouldMarkRevenueAtTenPercentAsMatchWhenRelativeDifferenceUsesTheLargerValue() {
        assertThat(compareRevenue(900L, 1000L).revenue().status())
                .isEqualTo(DailyGameComparisonStatus.MATCH);
    }

    @Test
    @DisplayName("[compare] Should Mark Revenue At Thirty Percent As Partial - When Relative Difference Uses The Larger Value")
    void shouldMarkRevenueAtThirtyPercentAsPartialWhenRelativeDifferenceUsesTheLargerValue() {
        assertThat(compareRevenue(700L, 1000L).revenue().status())
                .isEqualTo(DailyGameComparisonStatus.PARTIAL);
    }

    @Test
    @DisplayName("[compare] Should Mark Revenue Above Thirty Percent As No Match - When Relative Difference Uses The Larger Value")
    void shouldMarkRevenueAboveThirtyPercentAsNoMatchWhenRelativeDifferenceUsesTheLargerValue() {
        assertThat(compareRevenue(699L, 1000L).revenue().status())
                .isEqualTo(DailyGameComparisonStatus.NO_MATCH);
    }

    @Test
    @DisplayName("[compare] Should Compare Season Count With Direction - When Series Values Differ")
    void shouldCompareSeasonCountWithDirectionWhenSeriesValuesDiffer() {
        DailyChallenge challenge = seriesChallenge(snapshot("seasons", 5));
        TmdbTvFullDetails candidate = series("1396", 4, List.of(), List.of(), List.of(), List.of());
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, seriesIdentity("1396"));

        assertThat(feedback.seasons().status()).isEqualTo(DailyGameComparisonStatus.NO_MATCH);
        assertThat(feedback.seasons().direction()).isEqualTo(DailyGameComparisonDirection.SECRET_HIGHER);
        assertThat(feedback.seasons().displayValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("[compare] Should Mark Missing Metadata As No Data - When Either Side Lacks A Value")
    void shouldMarkMissingMetadataAsNoDataWhenEitherSideLacksAValue() {
        DailyChallenge challenge = movieChallenge(snapshot(
                "platforms", List.of("Netflix"),
                "revenue", 1000L));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, null,
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));

        DailyGameInfoFeedbackDTO feedback = new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(challenge, movieIdentity("680"));

        assertThat(feedback.platforms().status()).isEqualTo(DailyGameComparisonStatus.NO_DATA);
        assertThat(feedback.revenue().status()).isEqualTo(DailyGameComparisonStatus.NO_DATA);
        assertThat(feedback.platforms().displayValue()).isNull();
        assertThat(feedback.revenue().matchedValues()).isEmpty();
    }

    @Test
    @DisplayName("[compare] Should Propagate Tmdb Unavailable - When Candidate Detail Lookup Is Unavailable")
    void shouldPropagateTmdbUnavailableWhenCandidateDetailLookupIsUnavailable() {
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> new DailyGameInfoComparisonServiceImpl(tmdbClient)
                .compare(movieChallenge(snapshot("year", 1994)), movieIdentity("680")))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    private DailyGameInfoFeedbackDTO compareRevenue(Long secretRevenue, Long candidateRevenue) {
        DailyChallenge challenge = movieChallenge(snapshot("revenue", secretRevenue));
        TmdbMovieFullDetails candidate = movie("680", "Pulp Fiction", 1994, candidateRevenue,
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE)).thenReturn(found(candidate));
        return new DailyGameInfoComparisonServiceImpl(tmdbClient).compare(challenge, movieIdentity("680"));
    }

    private DailyChallenge movieChallenge(Map<String, Object> info) {
        return challenge(DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, info);
    }

    private DailyChallenge seriesChallenge(Map<String, Object> info) {
        return challenge(DailyGameType.SERIES_BY_INFO, DailyGameTargetKind.SERIES, info);
    }

    private DailyChallenge challenge(DailyGameType type, DailyGameTargetKind targetKind,
                                     Map<String, Object> info) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("targetKind", targetKind.name());
        snapshot.putAll(info);
        return DailyChallenge.builder()
                .challengeDate(LocalDate.of(2026, 9, 27))
                .gameType(type)
                .targetKind(targetKind)
                .targetTmdbId(targetKind == DailyGameTargetKind.MOVIE ? "550" : "1396")
                .answerKey(targetKind.name() + ":" + (targetKind == DailyGameTargetKind.MOVIE ? "550" : "1396"))
                .imagePath("/poster.jpg")
                .answerSnapshot(snapshot)
                .displaySnapshot(Map.of("imageUrl", "/poster.jpg"))
                .build();
    }

    private DailyGameCandidateIdentity movieIdentity(String id) {
        return new DailyGameCandidateIdentity(DailyGameTargetKind.MOVIE, id, null, null, null, null);
    }

    private DailyGameCandidateIdentity seriesIdentity(String id) {
        return new DailyGameCandidateIdentity(DailyGameTargetKind.SERIES, id, null, null, null, null);
    }

    private TmdbMovieFullDetails movie(String id, String title, int year, Long revenue,
                                       List<TmdbGenre> genres, List<String> platforms, List<String> cast,
                                       List<TmdbCrewMember> crew, List<TmdbProductionCompany> companies) {
        return new TmdbMovieFullDetails(id, title, title, null, "/poster.jpg", null, year + "-01-01", null,
                genres, List.of(), new TmdbCredits(cast.stream()
                .map(name -> new TmdbCastMember(null, name, null, null)).toList(), crew),
                providers(platforms), null, null, revenue, companies, null);
    }

    private TmdbTvFullDetails series(String id, int seasons, List<String> creators,
                                     List<TmdbGenre> genres, List<String> cast,
                                     List<TmdbProductionCompany> companies) {
        return new TmdbTvFullDetails(id, "Series", "Series", null, "/poster.jpg", null, "2000-01-01",
                List.of(), genres, List.of(), creators.stream().map(name -> new com.watchwise.watchwise_api.common.tmdb.TmdbCreator(null, name, null)).toList(),
                List.of(), null, new com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits(
                cast.stream().map(name -> new com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember(null, name, null, List.of(), null)).toList(), List.of()),
                providers(List.of()), null, seasons, 10, companies, null, "Ended");
    }

    private TmdbWatchProviders providers(List<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        return new TmdbWatchProviders(Map.of("BR", new TmdbRegionProviders(
                names.stream().map(name -> new TmdbProvider(name, null)).toList(), List.of(), List.of())));
    }

    private Map<String, Object> snapshot(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }
}
