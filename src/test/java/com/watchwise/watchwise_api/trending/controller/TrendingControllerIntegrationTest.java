package com.watchwise.watchwise_api.trending.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.trending.dto.TrendingCardDTO;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingService;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TrendingControllerIntegrationTest {

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
    private TrendingService trendingService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(trendingService);
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
    @DisplayName("[GET /trending] Should Return Trending Sections - When Request Is Authenticated")
    void shouldReturnTrendingSectionsWhenRequestIsAuthenticated() throws Exception {
        RegisteredUser user = registerUser("trendingvalid");
        TrendingResponseDTO expected = new TrendingResponseDTO(
                List.of(new TrendingCardDTO(
                        "603", MovieOrSeriesType.MOVIE, "The Matrix",
                        "https://image.tmdb.org/t/p/w500/matrix.jpg", 1999,
                        List.of(28, 878), 8.7, 123.4, 136, null, null,
                        ContentPreviewStatus.AVAILABLE)),
                List.of(new TrendingCardDTO(
                        "1396", MovieOrSeriesType.SERIES, "Breaking Bad",
                        "https://image.tmdb.org/t/p/w500/breaking-bad.jpg", 2008,
                        List.of(18, 80), 9.1, 456.7, 47, 5, null,
                        ContentPreviewStatus.AVAILABLE)));
        when(trendingService.getTrending(user.id(), TrendingTimeWindow.DAY, 21)).thenReturn(expected);

        mockMvc.perform(get("/trending")
                        .param("timeWindow", "day")
                        .param("size", "21")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movies").isArray())
                .andExpect(jsonPath("$.movies[0].tmdbId").value("603"))
                .andExpect(jsonPath("$.movies[0].type").value("MOVIE"))
                .andExpect(jsonPath("$.movies[0].title").value("The Matrix"))
                .andExpect(jsonPath("$.movies[0].posterUrl").value("https://image.tmdb.org/t/p/w500/matrix.jpg"))
                .andExpect(jsonPath("$.movies[0].year").value(1999))
                .andExpect(jsonPath("$.movies[0].tmdbVoteAverage").value(8.7))
                .andExpect(jsonPath("$.movies[0].popularity").value(123.4))
                .andExpect(jsonPath("$.movies[0].genres[0]").value(28))
                .andExpect(jsonPath("$.series").isArray())
                .andExpect(jsonPath("$.series[0].tmdbId").value("1396"))
                .andExpect(jsonPath("$.series[0].type").value("SERIES"))
                .andExpect(jsonPath("$.series[0].title").value("Breaking Bad"))
                .andExpect(jsonPath("$.series[0].posterUrl").value("https://image.tmdb.org/t/p/w500/breaking-bad.jpg"))
                .andExpect(jsonPath("$.series[0].year").value(2008))
                .andExpect(jsonPath("$.series[0].numberOfSeasons").value(5));

        verify(trendingService).getTrending(user.id(), TrendingTimeWindow.DAY, 21);
    }

    @Test
    @DisplayName("[GET /trending/{type}] Should Return Independent Continuation - When Movie Page Is Requested")
    void shouldReturnIndependentContinuationWhenMoviePageIsRequested() throws Exception {
        RegisteredUser user = registerUser("trendingcontinuation");
        TrendingResponseDTO expected = new TrendingResponseDTO(
                List.of(new TrendingCardDTO(
                        "604", MovieOrSeriesType.MOVIE, "The Matrix Reloaded", null, 2003,
                        null, 7.2, 90.0, null, null, null, ContentPreviewStatus.UNAVAILABLE)),
                List.of(),
                new TrendingResponseDTO.SectionPage(2, 12, 4, 80, true),
                null);
        when(trendingService.getTrendingSection(user.id(), MovieOrSeriesType.MOVIE,
                TrendingTimeWindow.DAY, 2, 12)).thenReturn(expected);

        mockMvc.perform(get("/trending/movie")
                        .param("timeWindow", "day")
                        .param("page", "2")
                        .param("size", "12")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movies").isArray())
                .andExpect(jsonPath("$.movies[0].tmdbId").value("604"))
                .andExpect(jsonPath("$.moviesPage.page").value(2))
                .andExpect(jsonPath("$.moviesPage.hasNext").value(true))
                .andExpect(jsonPath("$.series").isArray())
                .andExpect(jsonPath("$.series").isEmpty());

        verify(trendingService).getTrendingSection(user.id(),
                MovieOrSeriesType.MOVIE,
                TrendingTimeWindow.DAY, 2, 12);
    }

    @Test
    @DisplayName("[GET /trending] Should Return Unauthorized - When Access Token Is Missing")
    void shouldReturnUnauthorizedWhenAccessTokenIsMissing() throws Exception {
        mockMvc.perform(get("/trending").param("timeWindow", "day"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[GET /trending] Should Return BadGateway ApiError - When TMDB Is Unavailable")
    void shouldReturnBadGatewayApiErrorWhenTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("trendingtmdbdown");
        when(trendingService.getTrending(user.id(), TrendingTimeWindow.DAY, 12))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/trending")
                        .param("timeWindow", "day")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.path").value("/trending"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }
}
