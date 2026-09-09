package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMultiSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.dto.SearchContentDTO;
import com.watchwise.watchwise_api.search.dto.SearchPersonDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.dto.SearchUserListDTO;
import com.watchwise.watchwise_api.search.service.SearchType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private UserListItemService userListItemService;

    private SearchServiceImpl service;
    private UUID viewerId;

    @BeforeEach
    void setUp() {
        service = new SearchServiceImpl(userRepository, userListRepository, userListItemService, tmdbClient, new PageRequestFactory());
        viewerId = UUID.randomUUID();
    }

    private void stubViewer() {
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(
                User.builder().id(viewerId).preferredLanguage("pt-BR").build()));
    }

    @Test
    @DisplayName("[search] Should Map Movie Cards - When Searching Movies")
    void shouldMapMovieCardsWhenSearchingMovies() {
        stubViewer();
        TmdbSearchPage<TmdbMovieSearchResult> moviePage = new TmdbSearchPage<>(2,
                List.of(new TmdbMovieSearchResult("348", "Alien", "/alien.jpg", "1979-05-25")), 3, 41);
        when(tmdbClient.searchMovies("Alien", "pt-BR", 2)).thenReturn(new TmdbLookupResult.Found<>(moviePage));

        SearchResultDTO result = service.search(viewerId, " Alien ", SearchType.MOVIE, 2, 20);

        assertThat(result.contents()).containsExactly(
                new SearchContentDTO("348", MovieOrSeriesType.MOVIE, "Alien",
                        "https://image.tmdb.org/t/p/w500/alien.jpg", 1979));
        assertThat(result.people()).isEmpty();
        assertThat(result.lists()).isEmpty();
        assertThat(result.users()).isEmpty();
        verify(userRepository).findById(viewerId);
    }

    @Test
    @DisplayName("[search] Should Map Series Cards With Null Year - When Air Date Is Invalid")
    void shouldMapSeriesCardsWithNullYearWhenAirDateIsInvalid() {
        stubViewer();
        TmdbSearchPage<TmdbTvSearchResult> seriesPage = new TmdbSearchPage<>(1,
                List.of(new TmdbTvSearchResult("1396", "Breaking Bad", "/breaking-bad.jpg", "not-a-date")), 1, 1);
        when(tmdbClient.searchTv("Breaking Bad", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(seriesPage));

        SearchResultDTO result = service.search(viewerId, "Breaking Bad", SearchType.SERIES, 1, 20);

        assertThat(result.contents()).containsExactly(
                new SearchContentDTO("1396", MovieOrSeriesType.SERIES, "Breaking Bad",
                        "https://image.tmdb.org/t/p/w500/breaking-bad.jpg", null));
        assertThat(result.people()).isEmpty();
    }

    @Test
    @DisplayName("[search] Should Map Person Cards - When Searching People")
    void shouldMapPersonCardsWhenSearchingPeople() {
        stubViewer();
        TmdbSearchPage<TmdbPersonSearchResult> peoplePage = new TmdbSearchPage<>(1,
                List.of(new TmdbPersonSearchResult("287", "Sigourney Weaver", null)), 1, 1);
        when(tmdbClient.searchPeople("Sigourney Weaver", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Found<>(peoplePage));

        SearchResultDTO result = service.search(viewerId, "Sigourney Weaver", SearchType.PERSON, 1, 20);

        assertThat(result.people()).containsExactly(new SearchPersonDTO("287", "Sigourney Weaver", null));
        assertThat(result.contents()).isEmpty();
    }

    @Test
    @DisplayName("[search] Should Preserve Null Image Fields - When TMDB Has No Image Paths")
    void shouldPreserveNullImageFieldsWhenTmdbHasNoImagePaths() {
        stubViewer();
        when(tmdbClient.searchMulti("Unknown", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSearchPage<>(1, List.of(
                        new TmdbMultiSearchResult("1", "movie", "Unknown", null, null, null, null, null),
                        new TmdbMultiSearchResult("2", "person", null, "Unknown", null, null, null, null),
                        new TmdbMultiSearchResult("3", "tv", null, "Unknown Series", null, null, null, null)),
                        1, 3)));
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq("Unknown"), eq("Unknown"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Unknown"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());

        SearchResultDTO result = service.search(viewerId, "Unknown", null, 1, 20);

        assertThat(result.contents()).extracting(SearchContentDTO::posterUrl)
                .containsExactly(null, null);
        assertThat(result.people()).extracting(SearchPersonDTO::photo)
                .containsExactly((String) null);
    }

    @Test
    @DisplayName("[search] Should Return Empty External Results - When TMDB Returns Not Found")
    void shouldReturnEmptyExternalResultsWhenTmdbReturnsNotFound() {
        stubViewer();
        when(tmdbClient.searchMovies("Unknown", "pt-BR", 1)).thenReturn(new TmdbLookupResult.NotFound<>());

        SearchResultDTO result = service.search(viewerId, "Unknown", SearchType.MOVIE, 1, 20);

        assertThat(result.contents()).isEmpty();
        assertThat(result.people()).isEmpty();
        assertThat(result.lists()).isEmpty();
        assertThat(result.users()).isEmpty();
    }

    @Test
    @DisplayName("[search] Should Return Empty External Results - When TMDB Page Has Null Results")
    void shouldReturnEmptyExternalResultsWhenTmdbPageHasNullResults() {
        stubViewer();
        when(tmdbClient.searchMovies("Unknown", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1, null, 0, 0)));

        SearchResultDTO result = service.search(viewerId, "Unknown", SearchType.MOVIE, 1, 20);

        assertThat(result.contents()).isEmpty();
        assertThat(result.people()).isEmpty();
        assertThat(result.lists()).isEmpty();
        assertThat(result.users()).isEmpty();
    }

    @Test
    @DisplayName("[search] Should Throw TMDB Unavailable - When External Search Is Unavailable")
    void shouldThrowTmdbUnavailableWhenExternalSearchIsUnavailable() {
        stubViewer();
        when(tmdbClient.searchPeople(anyString(), anyString(), anyInt())).thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.search(viewerId, "Ripley", SearchType.PERSON, 1, 20))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[search] Should Throw Not Found - When Viewer Does Not Exist")
    void shouldThrowNotFoundWhenViewerDoesNotExist() {
        UUID missingViewerId = UUID.randomUUID();
        when(userRepository.findById(missingViewerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(missingViewerId, "Alien", SearchType.MOVIE, 1, 20))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    @DisplayName("[search] Should Separate Multi Search Results Without Changing Their Relative Order - When Type Is Omitted")
    void shouldSeparateMultiSearchResultsWithoutChangingTheirRelativeOrderWhenTypeIsOmitted() {
        stubViewer();
        when(tmdbClient.searchMulti("Alien", "pt-BR", 2)).thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(2,
                List.of(
                        new TmdbMultiSearchResult("1", "tv", null, "Alien Nation", "/tv.jpg", null, null, "1989-03-27"),
                        new TmdbMultiSearchResult("2", "person", null, "Sigourney Weaver", null, "/sigourney.jpg", null, null),
                        new TmdbMultiSearchResult("3", "movie", "Alien", null, "/alien.jpg", null, "1979-05-25", null),
                        new TmdbMultiSearchResult("4", "collection", "Alien Collection", null, null, null, null, null),
                        new TmdbMultiSearchResult("5", "person", null, "Ridley Scott", null, "/ridley.jpg", null, null)), 3, 5)));
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq("Alien"), eq("Alien"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Alien"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());

        SearchResultDTO result = service.search(viewerId, " Alien ", null, 2, 20);

        assertThat(result.contents()).containsExactly(
                new SearchContentDTO("1", MovieOrSeriesType.SERIES, "Alien Nation",
                        "https://image.tmdb.org/t/p/w500/tv.jpg", 1989),
                new SearchContentDTO("3", MovieOrSeriesType.MOVIE, "Alien",
                        "https://image.tmdb.org/t/p/w500/alien.jpg", 1979));
        assertThat(result.people()).containsExactly(
                new SearchPersonDTO("2", "Sigourney Weaver",
                        "https://image.tmdb.org/t/p/w185/sigourney.jpg"),
                new SearchPersonDTO("5", "Ridley Scott",
                        "https://image.tmdb.org/t/p/w185/ridley.jpg"));
    }

    @Test
    @DisplayName("[search] Should Search Visible Lists With Escaped Query And Batched Enrichment - When Type Is List")
    void shouldSearchVisibleListsWithEscapedQueryAndBatchedEnrichmentWhenTypeIsList() {
        stubViewer();
        UUID listId = UUID.randomUUID();
        User owner = User.builder().id(UUID.randomUUID()).username("marina").profilePicture("marina.png").isProfilePublic(false).build();
        UserList list = UserList.builder().id(listId).user(owner).name("Sci-fi 100%_\\").build();
        ContentRefDTO preview = new ContentRefDTO(UUID.randomUUID(), "348", ContentType.MOVIE, null, null, null, null, null, null, null);
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("sci-fi 100\\%\\_\\\\"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(listId))).thenReturn(Map.of(listId, List.of(preview)));
        when(userListItemService.countNestedListsByListIds(List.of(listId))).thenReturn(Map.of(listId, 2L));

        SearchResultDTO result = service.search(viewerId, " sci-fi 100%_\\ ", SearchType.LIST, 1, 10);

        assertThat(result.lists()).containsExactly(new SearchUserListDTO(
                listId, new UserPreviewDTO(owner.getId(), "marina", "marina.png", false), "Sci-fi 100%_\\", List.of(preview), 2L));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userListRepository).findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("sci-fi 100\\%\\_\\\\"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        verify(userListItemService).getPreviewItemsByListIds(List.of(listId));
        verify(userListItemService).countNestedListsByListIds(List.of(listId));
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[search] Should Search Users With Escaped Prefix And Safe Preview Mapping - When Type Is User")
    void shouldSearchUsersWithEscapedPrefixAndSafePreviewMappingWhenTypeIsUser() {
        stubViewer();
        User matchedUser = User.builder().id(UUID.randomUUID()).username("marina_100%").profilePicture("marina.png").isProfilePublic(false).build();
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq("marina_100%"), eq("marina\\_100\\%"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(matchedUser)));

        SearchResultDTO result = service.search(viewerId, " marina_100% ", SearchType.USER, 1, 10);

        assertThat(result.users()).containsExactly(new UserPreviewDTO(
                matchedUser.getId(), "marina_100%", "marina.png", false));
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findByUsernameStartingWithIgnoreCase(eq("marina_100%"), eq("marina\\_100\\%"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        verifyNoInteractions(tmdbClient);
        verifyNoInteractions(userListRepository, userListItemService);
    }

    @Test
    @DisplayName("[search] Should Combine Multi Search And Local Results - When Type Is Omitted")
    void shouldCombineMultiSearchAndLocalResultsWhenTypeIsOmitted() {
        stubViewer();
        User matchedUser = User.builder().id(UUID.randomUUID()).username("alienfan").profilePicture("alien.png").isProfilePublic(true).build();
        UUID listId = UUID.randomUUID();
        UserList list = UserList.builder().id(listId).user(matchedUser).name("Alien favorites").build();
        when(tmdbClient.searchMulti("Alien", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1,
                List.of(new TmdbMultiSearchResult("348", "movie", "Alien", null, "/alien.jpg", null, "1979-05-25", null)), 1, 1)));
        when(userRepository.findByUsernameStartingWithIgnoreCase(eq("Alien"), eq("Alien"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(matchedUser)));
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Alien"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(listId))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(listId))).thenReturn(Map.of());

        SearchResultDTO result = service.search(viewerId, "Alien", null, 1, 20);

        assertThat(result.contents()).containsExactly(new SearchContentDTO(
                "348", MovieOrSeriesType.MOVIE, "Alien",
                "https://image.tmdb.org/t/p/w500/alien.jpg", 1979));
        assertThat(result.users()).containsExactly(new UserPreviewDTO(matchedUser.getId(), "alienfan", "alien.png", true));
        assertThat(result.lists()).containsExactly(new SearchUserListDTO(
                listId, new UserPreviewDTO(matchedUser.getId(), "alienfan", "alien.png", true), "Alien favorites", List.of(), 0L));
    }

    @Test
    @DisplayName("[search] Should Return Empty Local Arrays And Batch Empty Enrichment - When List Page Is Empty")
    void shouldReturnEmptyLocalArraysAndBatchEmptyEnrichmentWhenListPageIsEmpty() {
        stubViewer();
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Nope"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());

        SearchResultDTO result = service.search(viewerId, " Nope ", SearchType.LIST, 1, 20);

        assertThat(result.contents()).isEmpty();
        assertThat(result.people()).isEmpty();
        assertThat(result.lists()).isEmpty();
        assertThat(result.users()).isEmpty();
        verify(userListItemService).getPreviewItemsByListIds(List.of());
        verify(userListItemService).countNestedListsByListIds(List.of());
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[search] Should Use Empty Enrichment Defaults - When A List Is Missing From Batch Maps")
    void shouldUseEmptyEnrichmentDefaultsWhenAListIsMissingFromBatchMaps() {
        stubViewer();
        UUID listId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        User owner = User.builder().id(ownerId).username("owner").build();
        UserList list = UserList.builder().id(listId).user(owner).name("Sci-fi").build();
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Sci-fi"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(listId))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(listId))).thenReturn(Map.of());

        SearchResultDTO result = service.search(viewerId, "Sci-fi", SearchType.LIST, 1, 20);

        assertThat(result.lists()).containsExactly(new SearchUserListDTO(
                listId,
                new UserPreviewDTO(ownerId, "owner", "https://default-image.png", true),
                "Sci-fi",
                List.of(),
                0L));
    }

    @Test
    @DisplayName("[search] Should Clamp Local Page And Pass Viewer Scope - When Searching Lists")
    void shouldClampLocalPageAndPassViewerScopeWhenSearchingLists() {
        stubViewer();
        when(userListRepository.findVisibleByNameContainingIgnoreCase(eq(viewerId), eq("Sci-fi"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());

        service.search(viewerId, " Sci-fi ", SearchType.LIST, 2, 99);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userListRepository).findVisibleByNameContainingIgnoreCase(
                eq(viewerId), eq("Sci-fi"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[search] Should Throw TMDB Unavailable - When Movie Search Is Unavailable")
    void shouldThrowTmdbUnavailableWhenMovieSearchIsUnavailable() {
        stubViewer();
        when(tmdbClient.searchMovies("Alien", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.search(viewerId, "Alien", SearchType.MOVIE, 1, 20))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[search] Should Throw TMDB Unavailable - When Series Search Is Unavailable")
    void shouldThrowTmdbUnavailableWhenSeriesSearchIsUnavailable() {
        stubViewer();
        when(tmdbClient.searchTv("Alien", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.search(viewerId, "Alien", SearchType.SERIES, 1, 20))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[search] Should Throw TMDB Unavailable - When Multi Search Is Unavailable")
    void shouldThrowTmdbUnavailableWhenMultiSearchIsUnavailable() {
        stubViewer();
        when(tmdbClient.searchMulti("Alien", "pt-BR", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> service.search(viewerId, "Alien", null, 1, 20))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[search] Should Return Entire TMDB Page - When Requested Size Is Below Twenty")
    void shouldReturnEntireTmdbPageWhenRequestedSizeIsBelowTwenty() {
        stubViewer();
        List<TmdbMovieSearchResult> movies = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> new TmdbMovieSearchResult(String.valueOf(index), "Movie " + index, null, "2000-01-01"))
                .toList();
        when(tmdbClient.searchMovies("Movie", "pt-BR", 2))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(2, movies, 2, 40)));

        SearchResultDTO result = service.search(viewerId, "Movie", SearchType.MOVIE, 2, 10);

        assertThat(result.contents()).hasSize(20).extracting(SearchContentDTO::tmdbId)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 20).mapToObj(String::valueOf).toList());
        verify(tmdbClient).searchMovies("Movie", "pt-BR", 2);
    }

    @Test
    @DisplayName("[search] Should Send Normalized Page And Truncate External Results - When Requested Size Exceeds Twenty")
    void shouldSendNormalizedPageAndTruncateExternalResultsWhenRequestedSizeExceedsTwenty() {
        stubViewer();
        List<TmdbMovieSearchResult> movies = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> new TmdbMovieSearchResult(String.valueOf(index), "Movie " + index, null, "2000-01-01"))
                .toList();
        when(tmdbClient.searchMovies("Movie", "pt-BR", 1)).thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1, movies, 2, 21)));

        SearchResultDTO result = service.search(viewerId, "Movie", SearchType.MOVIE, 0, 99);

        assertThat(result.contents()).hasSize(20).extracting(SearchContentDTO::tmdbId).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 20).mapToObj(String::valueOf).toList());
        verify(tmdbClient).searchMovies("Movie", "pt-BR", 1);
    }
}
