package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.search.dto.SearchContentDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.service.SearchService;
import com.watchwise.watchwise_api.search.service.SearchType;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(searchService);
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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
                .andExpect(jsonPath("$.users").isArray());

        verify(searchService).search(user.id(), "Alien", SearchType.MOVIE, 2, 10);
    }

    @Test
    @DisplayName("[search] Should Share Rate Limit Across Search Types")
    void shouldShareRateLimitAcrossSearchTypes() throws Exception {
        RegisteredUser user = registerUser("searchratelimit");
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(user.id(), "Alien", SearchType.USER, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.MOVIE, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.SERIES, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.LIST, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", SearchType.PERSON, null, null)).thenReturn(expected);
        when(searchService.search(user.id(), "Alien", null, null, null)).thenReturn(expected);

        SearchType[] searchTypes = {
                SearchType.USER,
                SearchType.MOVIE,
                SearchType.SERIES,
                SearchType.LIST,
                SearchType.PERSON,
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
            if (searchType == null) {
                verify(searchService, times(5)).search(
                        eq(user.id()), eq("Alien"), isNull(), isNull(), isNull());
            } else {
                verify(searchService, times(5)).search(
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
