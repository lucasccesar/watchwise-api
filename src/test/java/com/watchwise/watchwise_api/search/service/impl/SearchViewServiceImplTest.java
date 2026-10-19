package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.dto.SearchContentViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchPersonViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchViewDTO;
import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchViewServiceImplTest {

    private static final UUID VIEWER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserRepository userRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private SearchContentViewAssembler contentViewAssembler;

    private SearchViewServiceImpl service;
    private User viewer;

    @BeforeEach
    void setUp() {
        service = new SearchViewServiceImpl(userRepository, tmdbClient, contentViewAssembler);
        viewer = viewer();
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
    }

    @Test
    @DisplayName("[search] Should Read The Exact Visual Movie Window And Preserve Page Metadata - When Page Two Is Requested")
    void shouldReadExactVisualMovieWindowAndPreservePageMetadataWhenPageTwoIsRequested() {
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 1))
                .thenReturn(found(moviePage(1, 22, movies(1, 20))));
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 2))
                .thenReturn(found(moviePage(2, 22, movies(21, 2))));
        when(contentViewAssembler.assemble(any(User.class), anyList()))
                .thenReturn(movieViews("19", "20", "21", "22"));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.MOVIE, 2, 18);

        assertThat(result.contents()).hasSize(4);
        assertThat(result.totalResults()).isEqualTo(22L);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        ArgumentCaptor<List<SearchContentCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(contentViewAssembler).assemble(eq(viewer), candidates.capture());
        assertThat(candidates.getValue()).extracting(SearchContentCandidate::coordinate)
                .extracting(coordinate -> coordinate.tmdbId())
                .containsExactly("19", "20", "21", "22");
        verify(tmdbClient).searchMovies("night", "pt-BR", "BR", 1);
        verify(tmdbClient).searchMovies("night", "pt-BR", "BR", 2);
    }

    @Test
    @DisplayName("[search] Should Read Series And People With Their Own Visual Windows - When Their Scopes Are Requested")
    void shouldReadSeriesAndPeopleWithTheirOwnVisualWindowsWhenTheirScopesAreRequested() {
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(tvPage(1, 1, List.of(new TmdbTvSearchResult(
                        "series-1", "Night Watch", "/series.jpg", "2024-01-02", null, 7.4d)))));
        when(tmdbClient.searchPeople("night", "pt-BR", 1))
                .thenReturn(found(personPage(1, 1, List.of(new TmdbPersonSearchResult(
                        "person-1", "Night Actor", "/person.jpg", "Acting")))));
        when(contentViewAssembler.assemble(any(User.class), anyList()))
                .thenReturn(List.of(new SearchContentViewDTO(
                        "series-1", MovieOrSeriesType.SERIES, "Night Watch", null,
                        2024, List.of(), null, null, 7.4d, null, null)));

        SearchViewDTO series = service.search(VIEWER_ID, "night", SearchViewScope.SERIES, 1, 18);
        SearchViewDTO people = service.search(VIEWER_ID, "night", SearchViewScope.PERSON, 1, 18);

        assertThat(series.contents()).singleElement().extracting(SearchContentViewDTO::tmdbId)
                .isEqualTo("series-1");
        assertThat(series.totalResults()).isEqualTo(1L);
        assertThat(people.people()).singleElement()
                .extracting(SearchPersonViewDTO::knownForDepartment)
                .isEqualTo("Acting");
        assertThat(people.people()).singleElement().extracting(SearchPersonViewDTO::photoUrl)
                .isEqualTo("https://image.tmdb.org/t/p/w185/person.jpg");
    }

    @Test
    @DisplayName("[search] Should Interleave Content Sources And Keep Source Exhaustion Order - When Content Scope Starts On A Later Page")
    void shouldInterleaveContentSourcesAndKeepSourceExhaustionOrderWhenContentScopeStartsOnALaterPage() {
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 1))
                .thenReturn(found(moviePage(1, 4, movies(1, 4))));
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(tvPage(1, 3, tvShows(1, 3))));
        when(contentViewAssembler.assemble(any(User.class), anyList()))
                .thenAnswer(invocation -> viewsForCandidates(invocation.getArgument(1)));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.CONTENT, 2, 4);

        assertThat(result.totalResults()).isEqualTo(7L);
        assertThat(result.contents()).extracting(SearchContentViewDTO::type)
                .containsExactly(MovieOrSeriesType.MOVIE, MovieOrSeriesType.SERIES, MovieOrSeriesType.MOVIE);
        verify(tmdbClient, atLeastOnce()).searchMovies("night", "pt-BR", "BR", 1);
        verify(tmdbClient, atLeastOnce()).searchTv("night", "pt-BR", 1);
    }

    @Test
    @DisplayName("[search] Should Not Substitute A Later Movie Rank - When An Earlier Rank Is Missing During Interleaving")
    void shouldNotSubstituteLaterMovieRankWhenAnEarlierRankIsMissingDuringInterleaving() {
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 1))
                .thenReturn(found(moviePage(1, 22, movies(1, 19))));
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 2))
                .thenReturn(found(moviePage(2, 22, movies(21, 2))));
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(tvPage(1, 22, tvShows(1, 20))));
        when(tmdbClient.searchTv("night", "pt-BR", 2))
                .thenReturn(found(tvPage(2, 22, tvShows(21, 2))));
        when(contentViewAssembler.assemble(any(User.class), anyList()))
                .thenAnswer(invocation -> viewsForCandidates(invocation.getArgument(1)));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.CONTENT, 7, 6);

        assertThat(result.contents()).extracting(SearchContentViewDTO::tmdbId)
                .containsExactly("19", "19", "20", "21", "21");
    }

    @Test
    @DisplayName("[search] Should Return An Empty Visual Page Without Assembling Cards - When No Source Has Results")
    void shouldReturnEmptyVisualPageWithoutAssemblingCardsWhenNoSourceHasResults() {
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 1))
                .thenReturn(found(moviePage(1, 0, List.of())));
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(tvPage(1, 0, List.of())));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.CONTENT, 1, 18);

        assertThat(result.contents()).isEmpty();
        assertThat(result.totalResults()).isZero();
        assertThat(result.totalPages()).isZero();
        verify(contentViewAssembler, never()).assemble(any(User.class), anyList());
    }

    @Test
    @DisplayName("[search] Should Propagate TmdbUnavailableException - When Movie Search Is Unavailable")
    void shouldPropagateTypedTmdbUnavailableFailureWhenMovieSearchIsUnavailable() {
        when(tmdbClient.searchMovies("night", "pt-BR", "BR", 1)).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.search(VIEWER_ID, "night", SearchViewScope.MOVIE, 1, 18))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB search is unavailable");
    }

    private User viewer() {
        return User.builder()
                .id(VIEWER_ID)
                .preferredLanguage("pt-BR")
                .preferredRegion("BR")
                .build();
    }

    private List<SearchContentViewDTO> movieViews(String... ids) {
        return List.of(ids).stream()
                .map(id -> new SearchContentViewDTO(
                        id, MovieOrSeriesType.MOVIE, "Title " + id, null,
                        null, List.of(), null, null, null, null, null))
                .toList();
    }

    private List<SearchContentViewDTO> viewsForCandidates(List<SearchContentCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> new SearchContentViewDTO(
                        candidate.coordinate().tmdbId(),
                        MovieOrSeriesType.valueOf(candidate.coordinate().type().name()),
                        candidate.title(), null, null, List.of(), null, null,
                        candidate.voteAverage(), null, null))
                .toList();
    }

    private List<TmdbMovieSearchResult> movies(int firstId, int count) {
        List<TmdbMovieSearchResult> movies = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            int id = firstId + offset;
            movies.add(new TmdbMovieSearchResult(
                    String.valueOf(id), "Movie " + id, "/movie-" + id + ".jpg", "2024-01-02", null, 8.1d));
        }
        return movies;
    }

    private List<TmdbTvSearchResult> tvShows(int firstId, int count) {
        List<TmdbTvSearchResult> shows = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            int id = firstId + offset;
            shows.add(new TmdbTvSearchResult(
                    String.valueOf(id), "Series " + id, "/series-" + id + ".jpg", "2024-01-02", null, 7.4d));
        }
        return shows;
    }

    private TmdbSearchPage<TmdbMovieSearchResult> moviePage(int page, long totalResults,
                                                             List<TmdbMovieSearchResult> results) {
        return new TmdbSearchPage<>(page, results, (int) ((totalResults + 19) / 20), totalResults);
    }

    private TmdbSearchPage<TmdbTvSearchResult> tvPage(int page, long totalResults,
                                                       List<TmdbTvSearchResult> results) {
        return new TmdbSearchPage<>(page, results, (int) ((totalResults + 19) / 20), totalResults);
    }

    private TmdbSearchPage<TmdbPersonSearchResult> personPage(int page, long totalResults,
                                                               List<TmdbPersonSearchResult> results) {
        return new TmdbSearchPage<>(page, results, (int) ((totalResults + 19) / 20), totalResults);
    }

    private <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }
}
