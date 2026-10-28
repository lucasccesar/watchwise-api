package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.search.dto.SearchContentDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.search.service.SearchService;
import com.watchwise.watchwise_api.search.service.SearchType;
import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.search.service.SearchViewService;
import com.watchwise.watchwise_api.search.service.impl.SearchViewServiceImpl;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SearchControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender emailConfirmationSender;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @Autowired
    private SearchViewService searchViewService;

    @MockitoBean
    private SearchService searchService;

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(searchService);
        reset(tmdbClient);
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        var user = com.watchwise.watchwise_api.user.service.TestRegistrationHelper.register(
                mockMvc, userRepository, emailConfirmationSender,
                username, username, username + "@email.com", true);
        return new RegisteredUser(user.id(), user.accessToken());
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        String body = """
                {
                    "username": "%s",
                    "name": "%s",
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("[searchView] Should Load The Concrete Service - When The Application Context Starts")
    void shouldLoadConcreteSearchViewServiceWhenApplicationContextStarts() {
        assertThat(searchViewService).isInstanceOf(SearchViewServiceImpl.class);
    }

    @Test
    @DisplayName("[searchView] Should Return Unauthorized - When Access Token Is Missing")
    void shouldReturnUnauthorizedWhenSearchViewAccessTokenIsMissing() throws Exception {
        assertSearchViewApiError(
                mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())),
                401)
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("[searchView] Should Return BadRequest ApiError - When Scope Is Invalid")
    void shouldReturnBadRequestApiErrorWhenSearchViewScopeIsInvalid() throws Exception {
        RegisteredUser user = registerUser("searchviewinvalidscope");

        assertSearchViewApiError(
                mockMvc.perform(searchViewRequest(user.accessToken()).param("scope", "INVALID")),
                400)
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("[searchView] Should Return BadRequest ApiError - When Scope Is Missing")
    void shouldReturnBadRequestApiErrorWhenSearchViewScopeIsMissing() throws Exception {
        RegisteredUser user = registerUser("searchviewmissingscope");

        assertSearchViewApiError(
                mockMvc.perform(searchViewRequest(user.accessToken())),
                400)
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("[searchView] Should Return BadRequest ApiError - When Page Is Zero")
    void shouldReturnBadRequestApiErrorWhenSearchViewPageIsZero() throws Exception {
        RegisteredUser user = registerUser("searchviewpagezero");

        assertSearchViewApiError(
                mockMvc.perform(searchViewRequest(user.accessToken())
                        .param("scope", SearchViewScope.MOVIE.name())
                        .param("page", "0")),
                400)
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("[searchView] Should Return BadRequest ApiError - When Size Exceeds Twenty")
    void shouldReturnBadRequestApiErrorWhenSearchViewSizeExceedsTwenty() throws Exception {
        RegisteredUser user = registerUser("searchviewsizeabovemaximum");

        assertSearchViewApiError(
                mockMvc.perform(searchViewRequest(user.accessToken())
                        .param("scope", SearchViewScope.MOVIE.name())
                        .param("size", "21")),
                400)
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @DisplayName("[searchView] Should Trim Query And Apply Default Page And Size - When Pagination Is Omitted")
    void shouldTrimQueryAndApplyDefaultPageAndSizeWhenPaginationIsOmitted() throws Exception {
        RegisteredUser user = registerUser("searchviewdefaults");
        stubMovieSearch(1, List.of(movie("movie-1")));

        mockMvc.perform(get("/search/view")
                        .param("q", " night ")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("MOVIE"))
                .andExpect(jsonPath("$.query").value("night"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(18))
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.contents[0].tmdbId").value("movie-1"));

        verify(tmdbClient).searchMovies("night", "en-US", "US", 1);
    }

    @Test
    @DisplayName("[searchView] Should Reject A Query - When The Trimmed Maximum Is Exceeded")
    void shouldRejectQueryWhenTheTrimmedMaximumIsExceeded() throws Exception {
        RegisteredUser user = registerUser("searchviewlongquery");

        mockMvc.perform(get("/search/view")
                        .param("q", "   " + "n".repeat(101) + "   ")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message")
                        .value("q must contain at most 100 characters after trimming"))
                .andExpect(jsonPath("$.path").value("/search/view"));

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[searchView] Should Reject Invalid Page And Size - When Validation Fails Before Calling TMDB")
    void shouldRejectInvalidPageAndSizeWhenValidationFailsBeforeCallingTmdb() throws Exception {
        RegisteredUser user = registerUser("searchviewpagingvalidation");

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .param("page", "0")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search/view"));

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .param("page", "501")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search/view"));

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .param("size", "21")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search/view"));

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[searchView] Should Serve Every Scope - When The Running Application Handles Each Scope")
    void shouldServeEveryScopeWhenTheRunningApplicationHandlesEachScope() throws Exception {
        RegisteredUser user = registerUser("searchviewscopes");
        stubMovieSearch(1, List.of(movie("movie-1")));
        stubSeriesSearch(1, List.of(series("series-1")));
        stubPeopleSearch(1, List.of(person("person-1")));

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.CONTENT.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents[*].tmdbId")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("movie-1", "series-1")))
                .andExpect(jsonPath("$.people").isArray())
                .andExpect(jsonPath("$.people").isEmpty());

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents[0].type").value("MOVIE"));

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.SERIES.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents[0].type").value("SERIES"));

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.PERSON.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.people[0].tmdbId").value("person-1"))
                .andExpect(jsonPath("$.people[0].knownForDepartment").value("Acting"));
    }

    @Test
    @DisplayName("[searchView] Should Return Mixed Content In Planned Page Order Without Persisting Content - When Content Scope Is Requested")
    void shouldReturnMixedContentInPlannedPageOrderWithoutPersistingContentWhenContentScopeIsRequested() throws Exception {
        RegisteredUser user = registerUser("searchviewmixedcontent");
        stubMovieSearch(4, List.of(movie("movie-1"), movie("movie-2"), movie("movie-3"), movie("movie-4")));
        stubSeriesSearch(3, List.of(series("series-1"), series("series-2"), series("series-3")));
        long contentCountBefore = contentRepository.count();

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.CONTENT.name())
                        .param("page", "2")
                        .param("size", "4")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(4))
                .andExpect(jsonPath("$.totalResults").value(7))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.contents[0].tmdbId").value("movie-3"))
                .andExpect(jsonPath("$.contents[1].tmdbId").value("series-3"))
                .andExpect(jsonPath("$.contents[2].tmdbId").value("movie-4"));

        assertThat(contentRepository.count()).isEqualTo(contentCountBefore);
        verify(tmdbClient, org.mockito.Mockito.atLeastOnce()).searchMovies("night", "en-US", "US", 1);
        verify(tmdbClient, org.mockito.Mockito.atLeastOnce()).searchTv("night", "en-US", 1);
    }

    @Test
    @DisplayName("[searchView] Should Map Typed Tmdb Failure To BadGateway - When TMDB Is Unavailable")
    void shouldMapTypedTmdbFailureToBadGatewayWhenTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("searchviewtmdbdown");
        when(tmdbClient.searchMovies(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.path").value("/search/view"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[searchView] Should Share The Legacy Search Rate Limit - When Search View Uses The Same Bucket")
    void shouldShareTheLegacySearchRateLimitWhenSearchViewUsesTheSameBucket() throws Exception {
        RegisteredUser user = registerUser("searchviewratelimit");
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(user.id(), "night", null, null, null)).thenReturn(expected);
        stubMovieSearch(1, List.of(movie("movie-1")));

        for (int i = 0; i < 29; i++) {
            mockMvc.perform(get("/search")
                            .param("q", "night")
                            .cookie(user.accessToken()))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/search/view")
                        .param("q", "night")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/search").param("q", "night").cookie(user.accessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        verify(searchService, times(29)).search(user.id(), "night", null, null, null);
    }

    private MockHttpServletRequestBuilder searchViewRequest(Cookie accessToken) {
        return get("/search/view")
                .param("q", "night")
                .cookie(accessToken);
    }

    private ResultActions assertSearchViewApiError(ResultActions resultActions, int expectedStatus)
            throws Exception {
        return resultActions
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.path").value("/search/view"))
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    private void stubMovieSearch(long totalResults, List<TmdbMovieSearchResult> results) {
        when(tmdbClient.searchMovies(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(found(new TmdbSearchPage<>(1, results, totalPages(totalResults), totalResults)));
    }

    private void stubSeriesSearch(long totalResults, List<TmdbTvSearchResult> results) {
        when(tmdbClient.searchTv(anyString(), anyString(), anyInt()))
                .thenReturn(found(new TmdbSearchPage<>(1, results, totalPages(totalResults), totalResults)));
    }

    private void stubPeopleSearch(long totalResults, List<TmdbPersonSearchResult> results) {
        when(tmdbClient.searchPeople(anyString(), anyString(), anyInt()))
                .thenReturn(found(new TmdbSearchPage<>(1, results, totalPages(totalResults), totalResults)));
    }

    private TmdbMovieSearchResult movie(String id) {
        return new TmdbMovieSearchResult(id, "Movie " + id, "/" + id + ".jpg", "2024-01-02");
    }

    private TmdbTvSearchResult series(String id) {
        return new TmdbTvSearchResult(id, "Series " + id, "/" + id + ".jpg", "2024-01-02");
    }

    private TmdbPersonSearchResult person(String id) {
        return new TmdbPersonSearchResult(id, "Person " + id, "/" + id + ".jpg", "Acting");
    }

    private int totalPages(long totalResults) {
        return totalResults == 0 ? 0 : (int) ((totalResults + 19) / 20);
    }

    private <T> TmdbLookupResult<T> found(T value) {
        return new TmdbLookupResult.Found<>(value);
    }

    @Test
    @DisplayName("[search] Should Return Search Result And Trim Query - When Request Is Valid")
    void shouldReturnSearchResultAndTrimQueryWhenRequestIsValid() throws Exception {
        RegisteredUser user = registerUser("searchvalid");
        SearchResultDTO expected = new SearchResultDTO(
                List.of(new SearchContentDTO("603", MovieOrSeriesType.MOVIE, "Alien", "/alien.jpg", 1979)),
                List.of(), List.of(), List.of());
        when(searchService.search(user.id(), "Alien", SearchType.MOVIE, 2, 10)).thenReturn(expected);

        mockMvc.perform(get("/search")
                        .param("q", " Alien ")
                        .param("type", "MOVIE")
                        .param("page", "2")
                        .param("size", "10")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents.length()").value(1))
                .andExpect(jsonPath("$.contents[0].tmdbId").value("603"))
                .andExpect(jsonPath("$.people").isArray())
                .andExpect(jsonPath("$.lists").isArray())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.templates").isArray());

        verify(searchService).search(user.id(), "Alien", SearchType.MOVIE, 2, 10);
    }

    @Test
    @DisplayName("[search] Should Accept Picks Template Search Type - When Type Is Explicit")
    void shouldAcceptPicksTemplateSearchTypeWhenTypeIsExplicit() throws Exception {
        RegisteredUser user = registerUser("searchpickstemplate");
        PicksTemplatePreviewDTO template = new PicksTemplatePreviewDTO(
                UUID.randomUUID(), PickOrigin.COMMUNITY, "Awards", null, null);
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of(), List.of(template));
        when(searchService.search(user.id(), "Awards", SearchType.PICKS_TEMPLATE, null, null)).thenReturn(expected);

        mockMvc.perform(get("/search")
                        .param("q", "Awards")
                        .param("type", SearchType.PICKS_TEMPLATE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates.length()").value(1))
                .andExpect(jsonPath("$.templates[0].name").value("Awards"));

        verify(searchService).search(user.id(), "Awards", SearchType.PICKS_TEMPLATE, null, null);
    }

    @Test
    @DisplayName("[search] Should Share Rate Limit Across Search Types - When Both Routes Are Used")
    void shouldShareRateLimitAcrossSearchTypesWhenBothRoutesAreUsed() throws Exception {
        RegisteredUser user = registerUser("searchratelimit");
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(user.id(), "Alien", SearchType.USER, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.MOVIE, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.SERIES, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.LIST, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.PERSON, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.PICKS_TEMPLATE, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", null, null, null)).thenReturn(expected);

        SearchType[] searchTypes = {
                SearchType.USER,
                SearchType.MOVIE,
                SearchType.SERIES,
                SearchType.LIST,
                SearchType.PERSON,
                SearchType.PICKS_TEMPLATE,
                null
        };

        for (int i = 0; i < 30; i++) {
            MockHttpServletRequestBuilder request = get("/search")
                    .param("q", "Alien")
                    .cookie(user.accessToken());
            SearchType searchType = searchTypes[i % searchTypes.length];
            if (searchType != null) {
                request.param("type", searchType.name());
            }

            mockMvc.perform(request)
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("type", SearchType.PERSON.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verify(searchService, times(30)).search(
                eq(user.id()), eq("Alien"), nullable(SearchType.class), isNull(), isNull());

        for (SearchType searchType : searchTypes) {
            int expectedCalls = 0;
            for (int i = 0; i < 30; i++) {
                if (searchTypes[i % searchTypes.length] == searchType) {
                    expectedCalls++;
                }
            }
            if (searchType == null) {
                verify(searchService, times(expectedCalls)).search(
                        eq(user.id()), eq("Alien"), isNull(), isNull(), isNull());
            } else {
                verify(searchService, times(expectedCalls)).search(
                        eq(user.id()), eq("Alien"), eq(searchType), isNull(), isNull());
            }
        }
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Query Is Missing")
    void shouldReturnBadRequestApiErrorWhenQueryIsMissing() throws Exception {
        RegisteredUser user = registerUser("searchmissingquery");

        mockMvc.perform(get("/search").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Query Has Fewer Than Three Characters")
    void shouldReturnBadRequestApiErrorWhenQueryHasFewerThanThreeCharacters() throws Exception {
        RegisteredUser user = registerUser("searchshortquery");

        mockMvc.perform(get("/search").param("q", "ab").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Trimmed Query Has Fewer Than Three Characters")
    void shouldReturnBadRequestApiErrorWhenTrimmedQueryHasFewerThanThreeCharacters() throws Exception {
        RegisteredUser user = registerUser("searchtrimquery");

        mockMvc.perform(get("/search").param("q", "  ab  ").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("q must contain at least 3 characters after trimming"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Accept Query At Maximum Length - When Query Has 100 Characters")
    void shouldAcceptQueryAtMaximumLengthWhenQueryHas100Characters() throws Exception {
        RegisteredUser user = registerUser("searchmaxquery");
        String query = "a".repeat(100);
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(user.id(), query, null, null, null)).thenReturn(expected);

        mockMvc.perform(get("/search")
                        .param("q", query)
                        .cookie(user.accessToken()))
                .andExpect(status().isOk());

        verify(searchService).search(user.id(), query, null, null, null);
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Query Exceeds 100 Characters")
    void shouldReturnBadRequestApiErrorWhenQueryExceeds100Characters() throws Exception {
        RegisteredUser user = registerUser("searchlongquery");
        String query = "a".repeat(101);

        mockMvc.perform(get("/search")
                        .param("q", query)
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation Failed"))
                .andExpect(jsonPath("$.errors[0].field").value("q"))
                .andExpect(jsonPath("$.errors[0].message").value("q must contain at most 100 characters"))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Type Is Invalid")
    void shouldReturnBadRequestApiErrorWhenTypeIsInvalid() throws Exception {
        RegisteredUser user = registerUser("searchinvalidtype");

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("type", "UNKNOWN")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Page Is Zero")
    void shouldReturnBadRequestApiErrorWhenPageIsZero() throws Exception {
        RegisteredUser user = registerUser("searchzeropage");

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("page", "0")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Size Exceeds Twenty")
    void shouldReturnBadRequestApiErrorWhenSizeExceedsTwenty() throws Exception {
        RegisteredUser user = registerUser("searchoversize");

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("size", "21")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Return Unauthorized - When Access Token Is Missing")
    void shouldReturnUnauthorizedWhenAccessTokenIsMissing() throws Exception {
        mockMvc.perform(get("/search").param("q", "Alien"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[search] Should Return BadGateway ApiError - When TMDB Is Unavailable")
    void shouldReturnBadGatewayApiErrorWhenTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("searchtmdbdown");
        when(searchService.search(user.id(), "Alien", SearchType.MOVIE, null, null))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("type", "MOVIE")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When External Page Exceeds TMDB Limit")
    void shouldReturnBadRequestApiErrorWhenExternalPageExceedsTmdbLimit() throws Exception {
        RegisteredUser user = registerUser("searchexternalpage");

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("type", "MOVIE")
                        .param("page", "501")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("page must be less than or equal to 500 for external searches"))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Omitted-Type External Page Exceeds TMDB Limit")
    void shouldReturnBadRequestApiErrorWhenOmittedTypeExternalPageExceedsTmdbLimit() throws Exception {
        RegisteredUser user = registerUser("searchomittedexternalpage");

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("page", "501")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("page must be less than or equal to 500 for external searches"))
                .andExpect(jsonPath("$.path").value("/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(searchService);
    }

    @Test
    @DisplayName("[search] Should Allow Page Above TMDB Limit - When Searching Local Lists")
    void shouldAllowPageAboveTmdbLimitWhenSearchingLocalLists() throws Exception {
        RegisteredUser user = registerUser("searchlocalpage");
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(user.id(), "Alien", SearchType.LIST, 501, null)).thenReturn(expected);

        mockMvc.perform(get("/search")
                        .param("q", "Alien")
                        .param("type", "LIST")
                        .param("page", "501")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents").isArray())
                .andExpect(jsonPath("$.lists").isArray());

        verify(searchService).search(user.id(), "Alien", SearchType.LIST, 501, null);
    }
}
