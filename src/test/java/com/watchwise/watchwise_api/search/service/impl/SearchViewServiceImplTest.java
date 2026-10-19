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
import com.watchwise.watchwise_api.search.service.ContentSearchPagePlanner;
import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.search.service.TmdbSearchWindowReader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchViewServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();

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
        viewer = User.builder()
                .id(VIEWER_ID)
                .preferredLanguage("pt-BR")
                .preferredRegion("BR")
                .build();
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        service = new SearchViewServiceImpl(
                userRepository,
                tmdbClient,
                contentViewAssembler);
    }

    @Test
    @DisplayName("[search] Should Return A Logical Movie Window - When Visual Page Starts Inside The First TMDB Page")
    void shouldReturnALogicalMovieWindowWhenVisualPageStartsInsideTheFirstTmdbPage() {
        when(tmdbClient.searchMovies("night", "pt-BR", 1))
                .thenReturn(found(moviePage(1, 22, 1, 20)));
        when(tmdbClient.searchMovies("night", "pt-BR", 2))
                .thenReturn(found(moviePage(2, 22, 21, 22)));
        when(contentViewAssembler.assemble(eq(viewer), anyList()))
                .thenReturn(List.of(
                        movieView("19"), movieView("20"), movieView("21"), movieView("22")));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.MOVIE, 2, 18);

        assertThat(result.contents()).hasSize(4);
        assertThat(result.totalResults()).isEqualTo(22L);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        verify(tmdbClient).searchMovies("night", "pt-BR", 1);
        verify(tmdbClient).searchMovies("night", "pt-BR", 2);
        ArgumentCaptor<List<SearchContentCandidate>> candidates = ArgumentCaptor.forClass(List.class);
        verify(contentViewAssembler).assemble(eq(viewer), candidates.capture());
        assertThat(candidates.getValue()).extracting(SearchContentCandidate::coordinate)
                .extracting(coordinate -> coordinate.tmdbId())
                .containsExactly("19", "20", "21", "22");
    }

    @Test
    @DisplayName("[search] Should Assemble Series Metadata - When Series Scope Is Requested")
    void shouldAssembleSeriesMetadataWhenSeriesScopeIsRequested() {
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(new TmdbSearchPage<>(1,
                        List.of(new TmdbTvSearchResult("201", "Night Watch", "/night-watch.jpg", "2024-01-01")),
                        1, 1)));
        SearchContentViewDTO series = new SearchContentViewDTO(
                "201", MovieOrSeriesType.SERIES, "Night Watch", "/night-watch.jpg", 2024,
                List.of("Drama"), null, 2, 8.2d, null, null);
        when(contentViewAssembler.assemble(eq(viewer), anyList())).thenReturn(List.of(series));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.SERIES, 1, 18);

        assertThat(result.contents()).containsExactly(series);
        assertThat(result.people()).isEmpty();
        assertThat(result.totalResults()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
        verify(tmdbClient).searchTv("night", "pt-BR", 1);
    }

    @Test
    @DisplayName("[search] Should Return Person Metadata Directly - When Person Scope Is Requested")
    void shouldReturnPersonMetadataDirectlyWhenPersonScopeIsRequested() {
        when(tmdbClient.searchPeople("night", "pt-BR", 1))
                .thenReturn(found(new TmdbSearchPage<>(1,
                        List.of(new TmdbPersonSearchResult("301", "Night Actor", "/night-actor.jpg", "Acting")),
                        1, 1)));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.PERSON, 1, 18);

        assertThat(result.people()).singleElement()
                .extracting(SearchPersonViewDTO::tmdbId, SearchPersonViewDTO::name,
                        SearchPersonViewDTO::photoUrl, SearchPersonViewDTO::knownForDepartment)
                .containsExactly("301", "Night Actor",
                        "https://image.tmdb.org/t/p/w185/night-actor.jpg", "Acting");
        assertThat(result.contents()).isEmpty();
        assertThat(result.totalResults()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
        verifyNoInteractions(contentViewAssembler);
    }

    @Test
    @DisplayName("[search] Should Page Content Sources And Preserve Exhaustion Metadata - When The Series Source Ends First")
    void shouldPageContentSourcesAndPreserveExhaustionMetadataWhenTheSeriesSourceEndsFirst() {
        when(tmdbClient.searchMovies("night", "pt-BR", 1))
                .thenReturn(found(moviePage(1, 6, 1, 6)));
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(new TmdbSearchPage<>(1,
                        List.of(new TmdbTvSearchResult("901", "Only Series", "/series.jpg", "2020-01-01")),
                        1, 1)));
        when(contentViewAssembler.assemble(eq(viewer), anyList()))
                .thenReturn(List.of(movieView("3"), movieView("4"), movieView("5")));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.CONTENT, 2, 4);

        assertThat(result.totalResults()).isEqualTo(7L);
        assertThat(result.contents()).extracting(SearchContentViewDTO::type)
                .containsExactly(MovieOrSeriesType.MOVIE, MovieOrSeriesType.MOVIE, MovieOrSeriesType.MOVIE);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
        verify(tmdbClient, atLeastOnce()).searchMovies("night", "pt-BR", 1);
        verify(tmdbClient, atLeastOnce()).searchTv("night", "pt-BR", 1);
    }

    @Test
    @DisplayName("[search] Should Read Only Required TMDB Source Pages - When Content Window Crosses A Source Page")
    void shouldReadOnlyRequiredTmdbSourcePagesWhenContentWindowCrossesASourcePage() {
        when(tmdbClient.searchMovies("night", "pt-BR", 1))
                .thenReturn(found(moviePage(1, 23, 1, 20)));
        when(tmdbClient.searchMovies("night", "pt-BR", 2))
                .thenReturn(found(moviePage(2, 23, 21, 23)));
        when(tmdbClient.searchTv("night", "pt-BR", 1))
                .thenReturn(found(new TmdbSearchPage<>(1, List.of(), 0, 0)));
        when(contentViewAssembler.assemble(eq(viewer), anyList()))
                .thenReturn(List.of(movieView("21"), movieView("22"), movieView("23")));

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.CONTENT, 6, 4);

        assertThat(result.contents()).hasSize(3);
        assertThat(result.totalResults()).isEqualTo(23L);
        assertThat(result.totalPages()).isEqualTo(6);
        assertThat(result.hasNext()).isFalse();
        verify(tmdbClient).searchMovies("night", "pt-BR", 2);
    }

    @Test
    @DisplayName("[search] Should Propagate TMDB Unavailable - When Movie Search Is Unavailable")
    void shouldPropagateTmdbUnavailableWhenMovieSearchIsUnavailable() {
        when(tmdbClient.searchMovies("night", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.search(VIEWER_ID, "night", SearchViewScope.MOVIE, 1, 18))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB search is unavailable");
    }

    @Test
    @DisplayName("[search] Should Return Empty Metadata - When Movie Search Is Not Found")
    void shouldReturnEmptyMetadataWhenMovieSearchIsNotFound() {
        when(tmdbClient.searchMovies("night", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.NotFound<>());
        when(contentViewAssembler.assemble(eq(viewer), anyList())).thenReturn(List.of());

        SearchViewDTO result = service.search(VIEWER_ID, "night", SearchViewScope.MOVIE, 1, 18);

        assertThat(result.scope()).isEqualTo(SearchViewScope.MOVIE);
        assertThat(result.contents()).isEmpty();
        assertThat(result.people()).isEmpty();
        assertThat(result.totalResults()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    private TmdbSearchPage<TmdbMovieSearchResult> moviePage(int page, long totalResults, int firstId, int lastId) {
        List<TmdbMovieSearchResult> results = java.util.stream.IntStream.rangeClosed(firstId, lastId)
                .mapToObj(id -> new TmdbMovieSearchResult(String.valueOf(id), "Movie " + id, "/movie-" + id + ".jpg",
                        "2024-01-01"))
                .toList();
        return new TmdbSearchPage<>(page, results, totalResults == 0 ? 0 : 2, totalResults);
    }

    private SearchContentViewDTO movieView(String tmdbId) {
        return new SearchContentViewDTO(
                tmdbId, MovieOrSeriesType.MOVIE, "Movie " + tmdbId, "/movie.jpg", 2024,
                List.of(), 100, null, 8.0d, null, null);
    }

    private <T> TmdbLookupResult<TmdbSearchPage<T>> found(TmdbSearchPage<T> page) {
        return new TmdbLookupResult.Found<>(page);
    }
}
