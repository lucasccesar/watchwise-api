package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateRole;
import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbCreator;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbNetwork;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRatings;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyChallengeGeneratorTest {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
    private static final LocalDate CHALLENGE_DATE = LocalDate.of(2026, 9, 27);

    @Mock
    private TmdbClient tmdbClient;

    private DailyChallengeSnapshotAssembler snapshotAssembler;

    @BeforeEach
    void setUp() {
        snapshotAssembler = new DailyChallengeSnapshotAssembler(new ObjectMapper());
    }

    @Test
    @DisplayName("Movie poster generator chooses a discovery page between 1 and 50")
    void moviePosterGeneratorChoosesPageInSupportedRange() {
        when(tmdbClient.getPopularMovies(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(moviePage(new TmdbMovieSearchResult("550", "Fight Club", "/fight.jpg", "1999-10-15"))));

        DailyChallengeCandidate candidate = new MovieByPosterGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.gameType()).isEqualTo(DailyGameType.MOVIE_BY_POSTER);
        assertThat(candidate.targetKind()).isEqualTo(DailyGameTargetKind.MOVIE);
        assertThat(candidate.answerKey()).isEqualTo("MOVIE:550");
        assertThat(candidate.imagePath()).isEqualTo("https://image.tmdb.org/t/p/w500/fight.jpg");
        verify(tmdbClient).getPopularMovies(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE));
    }

    @Test
    @DisplayName("Movie poster generator rejects a result without a poster")
    void moviePosterGeneratorRejectsMissingPoster() {
        when(tmdbClient.getPopularMovies(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(moviePage(new TmdbMovieSearchResult("550", "Fight Club", null, "1999-10-15"))));

        assertThat(new MovieByPosterGenerator(tmdbClient, snapshotAssembler).generate(CHALLENGE_DATE)).isEmpty();
    }

    @Test
    @DisplayName("Series poster generator creates the series answer key")
    void seriesPosterGeneratorCreatesSeriesAnswerKey() {
        when(tmdbClient.getPopularSeries(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(seriesPage(new TmdbTvSearchResult("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20"))));

        DailyChallengeCandidate candidate = new SeriesByPosterGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.answerKey()).isEqualTo("SERIES:1396");
        assertThat(candidate.targetTmdbId()).isEqualTo("1396");
    }

    @Test
    @DisplayName("Person by face deduplicates people from movie and series credits")
    void personByFaceDeduplicatesPeopleFromBothMediaKinds() {
        when(tmdbClient.getPopularMovies(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(moviePage(new TmdbMovieSearchResult("550", "Fight Club", "/fight.jpg", "1999-10-15"))));
        when(tmdbClient.getPopularSeries(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(seriesPage(new TmdbTvSearchResult("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20"))));
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE))
                .thenReturn(found(movieDetails("550", "Fight Club", new TmdbCredits(
                        List.of(new TmdbCastMember(287, "Brad Pitt", "Tyler Durden", "/brad.jpg")), List.of()))));
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE))
                .thenReturn(found(seriesDetails("1396", "Breaking Bad", new TmdbAggregateCredits(
                        List.of(new TmdbAggregateCastMember(287, "Brad Pitt", "/brad.jpg", List.of(new TmdbAggregateRole("Tyler")), 10)), List.of()))));

        DailyChallengeCandidate candidate = new PersonByFaceGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.answerKey()).isEqualTo("PERSON:287");
        assertThat(candidate.targetTmdbId()).isEqualTo("287");
        assertThat(candidate.answerSnapshot().get("title").asText()).isEqualTo("Brad Pitt");
    }

    @Test
    @DisplayName("Episode by frame skips specials, future episodes and missing stills")
    void episodeGeneratorUsesCompositeIdentityAndFiltersEpisodes() {
        when(tmdbClient.getPopularSeries(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(seriesPage(new TmdbTvSearchResult("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20"))));
        TmdbSeasonFullDetails season = new TmdbSeasonFullDetails(1, "Season 1", null, null, "2008-01-20", 1, List.of(
                        new TmdbEpisodeSummary(0, "Special", null, "2008-01-01", 10, "/special.jpg", List.of()),
                        new TmdbEpisodeSummary(1, "Missing frame", null, "2008-01-20", 10, null, List.of()),
                        new TmdbEpisodeSummary(2, "Future", null, "2026-09-28", 10, "/future.jpg", List.of()),
                        new TmdbEpisodeSummary(3, "Valid", null, "2026-09-27", 10, "/valid.jpg", List.of())), null, null);
        TmdbTvFullDetails series = seriesDetails("1396", "Breaking Bad", null, List.of(
                new TmdbSeasonSummary(0, "Specials", null, "2008-01-01", 1, null),
                new TmdbSeasonSummary(1, "Season 1", null, "2008-01-20", 4, null)));
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE)).thenReturn(found(series));
        when(tmdbClient.getSeasonFullDetails("1396", 1, LANGUAGE)).thenReturn(found(season));

        DailyChallengeCandidate candidate = new EpisodeByFrameGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.answerKey()).isEqualTo("EPISODE:1396:1:3");
        assertThat(candidate.targetTmdbId()).isNull();
        assertThat(candidate.seriesTmdbId()).isEqualTo("1396");
        assertThat(candidate.seasonNumber()).isEqualTo(1);
        assertThat(candidate.episodeNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("Movie info generator preserves clue order and omits missing certification")
    void movieInfoGeneratorPreservesClueOrderAndOmitsMissingCertification() {
        when(tmdbClient.getPopularMovies(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(moviePage(new TmdbMovieSearchResult("550", "Fight Club", "/fight.jpg", "1999-10-15"))));
        when(tmdbClient.getMovieFullDetails("550", LANGUAGE))
                .thenReturn(found(movieDetailsWithInfo("550")));
        when(tmdbClient.getMovieReleaseDates("550", LANGUAGE))
                .thenReturn(found(new TmdbMovieReleaseDates("550", List.of(
                        new TmdbRegionReleaseDates("US", List.of(new TmdbMovieReleaseDate("R", null, null, null, 3)))))));

        DailyChallengeCandidate candidate = new MovieByInfoGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.hints()).extracting(DailyChallengeCandidate.HintSnapshot::hintType)
                .containsExactly("PLATFORM", "GENRES", "YEAR", "DIRECTOR", "CAST", "PRODUCTION_COMPANIES", "REVENUE");
        assertThat(candidate.hints()).noneMatch(hint -> hint.hintType().equals("CERTIFICATION"));
    }

    @Test
    @DisplayName("Series info generator omits certification and movie-only financial hints")
    void seriesInfoGeneratorOmitsCertificationAndFinancialHints() {
        when(tmdbClient.getPopularSeries(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(seriesPage(new TmdbTvSearchResult("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20"))));
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE))
                .thenReturn(found(seriesDetailsWithInfo("1396")));
        when(tmdbClient.getTvContentRatings("1396", LANGUAGE))
                .thenReturn(found(new TmdbTvContentRatings("1396", List.of(
                        new TmdbTvContentRating("US", "TV-MA")))));

        DailyChallengeCandidate candidate = new SeriesByInfoGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.hints()).extracting(DailyChallengeCandidate.HintSnapshot::hintType)
                .containsExactly("PLATFORM", "GENRES", "YEAR", "CREATOR", "CAST", "NETWORKS_PRODUCTION_COMPANIES", "COUNTS");
        assertThat(candidate.hints()).noneMatch(hint -> hint.hintType().equals("REVENUE"));
        assertThat(candidate.hints()).noneMatch(hint -> hint.hintType().equals("CERTIFICATION"));
    }

    @Test
    @DisplayName("Movie filmography generator uses the movie as source and person as answer")
    void movieFilmographyGeneratorUsesMovieSourceAndPersonAnswer() {
        when(tmdbClient.getTopRatedMovies(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(moviePage(new TmdbMovieSearchResult("680", "Pulp Fiction", "/pulp.jpg", "1994-09-10"))));
        when(tmdbClient.getMovieFullDetails("680", LANGUAGE))
                .thenReturn(found(movieDetails("680", "Pulp Fiction", new TmdbCredits(
                        List.of(new TmdbCastMember(103, "Uma Thurman", "Mia", "/uma.jpg")), List.of()))));

        DailyChallengeCandidate candidate = new ActorByMovieFilmographyGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.answerKey()).isEqualTo("PERSON:103");
        assertThat(candidate.sourceTmdbId()).isEqualTo("680");
    }

    @Test
    @DisplayName("Series filmography generator uses aggregate cast and series source")
    void seriesFilmographyGeneratorUsesAggregateCastAndSeriesSource() {
        when(tmdbClient.getTopRatedSeries(intThat(page -> page >= 1 && page <= 50), eq(LANGUAGE)))
                .thenReturn(found(seriesPage(new TmdbTvSearchResult("1396", "Breaking Bad", "/breaking-bad.jpg", "2008-01-20"))));
        when(tmdbClient.getTvFullDetails("1396", LANGUAGE))
                .thenReturn(found(seriesDetails("1396", "Breaking Bad", new TmdbAggregateCredits(
                        List.of(new TmdbAggregateCastMember(17419, "Bryan Cranston", "/bryan.jpg", List.of(new TmdbAggregateRole("Walter")), 62)), List.of()))));

        DailyChallengeCandidate candidate = new ActorBySeriesFilmographyGenerator(tmdbClient, snapshotAssembler)
                .generate(CHALLENGE_DATE)
                .orElseThrow();

        assertThat(candidate.answerKey()).isEqualTo("PERSON:17419");
        assertThat(candidate.sourceTmdbId()).isEqualTo("1396");
    }

    private static <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }

    private static TmdbSearchPage<TmdbMovieSearchResult> moviePage(TmdbMovieSearchResult result) {
        return new TmdbSearchPage<>(1, List.of(result), 1, 1);
    }

    private static TmdbSearchPage<TmdbTvSearchResult> seriesPage(TmdbTvSearchResult result) {
        return new TmdbSearchPage<>(1, List.of(result), 1, 1);
    }

    private static TmdbMovieFullDetails movieDetails(String id, String title, TmdbCredits credits) {
        return new TmdbMovieFullDetails(id, title, title, null, "/poster.jpg", null, "1999-10-15", null,
                List.of(), List.of(), credits, null, null, null, null, List.of(), null);
    }

    private static TmdbMovieFullDetails movieDetailsWithInfo(String id) {
        TmdbCredits credits = new TmdbCredits(
                List.of(new TmdbCastMember(287, "Brad Pitt", "Tyler Durden", "/brad.jpg")),
                List.of(new com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember(7467, "David Fincher", "Director", null)));
        return new TmdbMovieFullDetails(id, "Fight Club", "Fight Club", "A story", "/fight.jpg", null, "1999-10-15", 139,
                List.of(new TmdbGenre(18, "Drama"), new TmdbGenre(53, "Thriller")), List.of(), credits,
                new TmdbWatchProviders(Map.of("BR", new TmdbRegionProviders(
                        List.of(new com.watchwise.watchwise_api.common.tmdb.TmdbProvider("Netflix", "/netflix.jpg")), List.of(), List.of()))),
                null, 63000000L, 100853753L,
                List.of(new TmdbProductionCompany(508, "Regency Enterprises", null, "US")), null);
    }

    private static TmdbTvFullDetails seriesDetails(String id, String name, TmdbAggregateCredits credits) {
        return new TmdbTvFullDetails(id, name, name, null, "/poster.jpg", null, "2008-01-20", List.of(),
                List.of(), List.of(), List.of(), List.of(), null, credits, null, null, 5, 62, List.of(), null, "Ended");
    }

    private static TmdbTvFullDetails seriesDetails(String id, String name, TmdbAggregateCredits credits,
                                                    List<TmdbSeasonSummary> seasons) {
        return new TmdbTvFullDetails(id, name, name, null, "/poster.jpg", null, "2008-01-20", List.of(),
                List.of(), List.of(), List.of(), seasons, null, credits, null, null, 1, 4, List.of(), null, "Ended");
    }

    private static TmdbTvFullDetails seriesDetailsWithInfo(String id) {
        return new TmdbTvFullDetails(id, "Breaking Bad", "Breaking Bad", null, "/breaking-bad.jpg", null, "2008-01-20", List.of(),
                List.of(new TmdbGenre(18, "Drama")), List.of(), List.of(new TmdbCreator(666, "Vince Gilligan", null)), List.of(), null,
                new TmdbAggregateCredits(List.of(new TmdbAggregateCastMember(17419, "Bryan Cranston", "/bryan.jpg", List.of(), 62)), List.of()),
                new TmdbWatchProviders(Map.of("BR", new TmdbRegionProviders(
                        List.of(new com.watchwise.watchwise_api.common.tmdb.TmdbProvider("Netflix", "/netflix.jpg")), List.of(), List.of()))),
                        null, 5, 62, List.of(new TmdbProductionCompany(1, "Sony Pictures Television", null, "US")), null, "Ended", null, List.of(
                        new TmdbNetwork(49, "AMC", null, "US")));
    }
}
