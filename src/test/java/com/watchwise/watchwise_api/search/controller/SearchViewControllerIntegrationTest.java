package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.dto.SearchContentViewDTO;
import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.search.service.impl.SearchContentViewAssembler;
import com.watchwise.watchwise_api.search.service.impl.SearchViewServiceImpl;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SearchViewControllerIntegrationTest {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @Autowired
    private SearchViewServiceImpl searchViewService;

    @MockitoBean
    private TmdbClient tmdbClient;

    @MockitoBean
    private SearchContentViewAssembler contentViewAssembler;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(tmdbClient, contentViewAssembler);
        RequestThrottlerTestSupport.reset(requestThrottler);
        assertThat(searchViewService).isNotNull();
    }

    @Test
    @DisplayName("[searchView] Should Serialize Visual DTO Through Concrete Service - When Movie Results Exist")
    void shouldSerializeVisualDtoThroughConcreteServiceWhenMovieResultsExist() throws Exception {
        RegisteredUser user = registerUser("visualconcretemovie");
        when(tmdbClient.searchMovies("night", "en-US", "US", 1))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSearchPage<>(1,
                        List.of(new TmdbMovieSearchResult("603", "Night", "/night.jpg", "2024-01-01")),
                        1, 1)));
        when(contentViewAssembler.assemble(any(User.class), anyList()))
                .thenReturn(List.of(new SearchContentViewDTO(
                        "603", MovieOrSeriesType.MOVIE, "Night", "/night-poster.jpg", 2024,
                        List.of("Drama"), 100, null, 8.2d, null, null)));

        mockMvc.perform(get("/search/view")
                        .param("q", " night ")
                        .param("scope", "MOVIE")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("MOVIE"))
                .andExpect(jsonPath("$.query").value("night"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(18))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.contents[0].tmdbId").value("603"))
                .andExpect(jsonPath("$.contents[0].type").value("MOVIE"))
                .andExpect(jsonPath("$.contents[0].title").value("Night"))
                .andExpect(jsonPath("$.people").isArray())
                .andExpect(jsonPath("$.people").isEmpty());
    }

    @Test
    @DisplayName("[searchView] Should Serialize Empty Visual Metadata - When Movie Search Is Not Found")
    void shouldSerializeEmptyVisualMetadataWhenMovieSearchIsNotFound() throws Exception {
        RegisteredUser user = registerUser("visualconcreteempty");
        when(tmdbClient.searchMovies("empty", "en-US", "US", 1))
                .thenReturn(new TmdbLookupResult.NotFound<>());
        when(contentViewAssembler.assemble(any(User.class), anyList())).thenReturn(List.of());

        mockMvc.perform(get("/search/view")
                        .param("q", "empty")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("MOVIE"))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.totalResults").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.contents").isArray())
                .andExpect(jsonPath("$.contents").isEmpty())
                .andExpect(jsonPath("$.people").isArray())
                .andExpect(jsonPath("$.people").isEmpty());
    }

    @Test
    @DisplayName("[searchView] Should Return BadGateway ApiError Through Concrete Service - When TMDB Is Unavailable")
    void shouldReturnBadGatewayApiErrorThroughConcreteServiceWhenTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("visualconcretedown");
        when(tmdbClient.searchMovies("down", "en-US", "US", 1))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        mockMvc.perform(get("/search/view")
                        .param("q", "down")
                        .param("scope", SearchViewScope.MOVIE.name())
                        .cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.path").value("/search/view"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    private record RegisteredUser(Cookie accessToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        var user = com.watchwise.watchwise_api.user.service.TestRegistrationHelper.register(
                mockMvc, userRepository, emailConfirmationSender,
                username, username, username + "@email.com", true);
        return new RegisteredUser(user.accessToken());
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        return post("/auth/register")
                .contentType("application/json")
                .content("""
                        {
                            "username": "%s",
                            "name": "%s",
                            "email": "%s@email.com",
                            "password": "Password123"
                        }
                        """.formatted(username, username, username));
    }
}
