package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContentControllerIntegrationTest {

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
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    private Cookie accessTokenCookie;
    private Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        contentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);

        MvcResult registerResult = mockMvc.perform(registerRequest("contentuser", "contentuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return New Reference - When Type Is Movie")
    void shouldCreateAndReturnNewReferenceWhenTypeIsMovie() throws Exception {
        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"550\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.tmdbId").value("550"))
                .andExpect(jsonPath("$.type").value("MOVIE"));

        assertThat(contentRepository.findByTmdbIdAndType("550", ContentType.MOVIE)).isPresent();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return New Reference - When Type Is Series")
    void shouldCreateAndReturnNewReferenceWhenTypeIsSeries() throws Exception {
        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"1399\", \"type\": \"SERIES\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tmdbId").value("1399"))
                .andExpect(jsonPath("$.type").value("SERIES"));

        assertThat(contentRepository.findByTmdbIdAndType("1399", ContentType.SERIES)).isPresent();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return New Reference - When Type Is Season")
    void shouldCreateAndReturnNewReferenceWhenTypeIsSeason() throws Exception {
        mockMvc.perform(referenceRequest("{ \"seriesTmdbId\": \"1399\", \"type\": \"SEASON\", \"seasonNumber\": 1 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesTmdbId").value("1399"))
                .andExpect(jsonPath("$.seasonNumber").value(1))
                .andExpect(jsonPath("$.type").value("SEASON"));

        assertThat(contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .isPresent();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Create And Return New Reference - When Type Is Episode")
    void shouldCreateAndReturnNewReferenceWhenTypeIsEpisode() throws Exception {
        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"EPISODE\", \"seasonNumber\": 1, \"episodeNumber\": 3 }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesTmdbId").value("1399"))
                .andExpect(jsonPath("$.seasonNumber").value(1))
                .andExpect(jsonPath("$.episodeNumber").value(3))
                .andExpect(jsonPath("$.type").value("EPISODE"));

        assertThat(contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, 3, ContentType.EPISODE))
                .isPresent();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Same Id And Not Duplicate Row - When Called Twice With The Same Movie Payload")
    void shouldReturnSameIdAndNotDuplicateRowWhenCalledTwiceWithTheSameMoviePayload() throws Exception {
        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"680\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isOk());

        Content existing = contentRepository.findByTmdbIdAndType("680", ContentType.MOVIE).orElseThrow();

        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"680\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existing.getId().toString()));

        assertThat(contentRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Same Id And Not Duplicate Row - When Called Twice With The Same Season Payload")
    void shouldReturnSameIdAndNotDuplicateRowWhenCalledTwiceWithTheSameSeasonPayload() throws Exception {
        String body = "{ \"seriesTmdbId\": \"1396\", \"type\": \"SEASON\", \"seasonNumber\": 2 }";

        mockMvc.perform(referenceRequest(body))
                .andExpect(status().isOk());

        Content existing = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1396", 2, null, ContentType.SEASON)
                .orElseThrow();

        mockMvc.perform(referenceRequest(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existing.getId().toString()));

        assertThat(contentRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Transfer IsSeriesFinale To New Season - When A Previous Season Already Had It")
    void shouldTransferIsSeriesFinaleToNewSeasonWhenAPreviousSeasonAlreadyHadIt() throws Exception {
        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"SEASON\", \"seasonNumber\": 5, \"isSeriesFinale\": true }"))
                .andExpect(status().isOk());

        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"SEASON\", \"seasonNumber\": 6, \"isSeriesFinale\": true }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSeriesFinale").value(true));

        Content oldSeason = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 5, null, ContentType.SEASON)
                .orElseThrow();
        Content newSeason = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 6, null, ContentType.SEASON)
                .orElseThrow();

        assertThat(oldSeason.getIsSeriesFinale()).isFalse();
        assertThat(newSeason.getIsSeriesFinale()).isTrue();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Conflict - When Another Episode Is Already Marked As The Season Finale And The New One Is Not Later")
    void shouldReturnConflictWhenAnotherEpisodeIsAlreadyMarkedAsTheSeasonFinaleAndTheNewOneIsNotLater() throws Exception {
        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"EPISODE\", \"seasonNumber\": 1, \"episodeNumber\": 8, \"isSeasonFinale\": true }"))
                .andExpect(status().isOk());

        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"EPISODE\", \"seasonNumber\": 1, \"episodeNumber\": 5, \"isSeasonFinale\": true }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Another episode is already marked as this season's finale"));
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Transfer IsSeasonFinale To New Episode - When A Previous Episode Already Had It")
    void shouldTransferIsSeasonFinaleToNewEpisodeWhenAPreviousEpisodeAlreadyHadIt() throws Exception {
        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"EPISODE\", \"seasonNumber\": 1, \"episodeNumber\": 8, \"isSeasonFinale\": true }"))
                .andExpect(status().isOk());

        mockMvc.perform(referenceRequest(
                        "{ \"seriesTmdbId\": \"1399\", \"type\": \"EPISODE\", \"seasonNumber\": 1, \"episodeNumber\": 11, \"isSeasonFinale\": true }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSeasonFinale").value(true));

        Content oldEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, 8, ContentType.EPISODE)
                .orElseThrow();
        Content newEpisode = contentRepository
                .findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, 11, ContentType.EPISODE)
                .orElseThrow();

        assertThat(oldEpisode.getIsSeasonFinale()).isFalse();
        assertThat(newEpisode.getIsSeasonFinale()).isTrue();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return TooManyRequests - When Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(referenceRequest("{ \"tmdbId\": \"550\", \"type\": \"MOVIE\" }"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"550\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return BadRequest - When Type Is Missing")
    void shouldReturnBadRequestWhenTypeIsMissing() throws Exception {
        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"550\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return BadRequest - When Type Is Movie And TmdbId Is Missing")
    void shouldReturnBadRequestWhenTypeIsMovieAndTmdbIdIsMissing() throws Exception {
        mockMvc.perform(referenceRequest("{ \"type\": \"MOVIE\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tmdbId must be provided when type is MOVIE or SERIES"));
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return BadRequest - When Type Is Season And TmdbId Is Present")
    void shouldReturnBadRequestWhenTypeIsSeasonAndTmdbIdIsPresent() throws Exception {
        mockMvc.perform(referenceRequest(
                        "{ \"tmdbId\": \"550\", \"seriesTmdbId\": \"1399\", \"type\": \"SEASON\", \"seasonNumber\": 1 }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tmdbId and episodeNumber must not be provided when type is SEASON"));

        assertThat(contentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return BadRequest With Accepted Values - When Type Value Is Not A Valid Enum Constant")
    void shouldReturnBadRequestWithAcceptedValuesWhenTypeValueIsNotAValidEnumConstant() throws Exception {
        mockMvc.perform(referenceRequest("{ \"tmdbId\": \"550\", \"type\": \"movie\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Invalid value 'movie' for field 'type'. Accepted values: MOVIE, SERIES, SEASON, EPISODE"));

        assertThat(contentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return UnsupportedMediaType With Supported Types - When Content-Type Is Not JSON")
    void shouldReturnUnsupportedMediaTypeWithSupportedTypesWhenContentTypeIsNotJson() throws Exception {
        mockMvc.perform(post("/contents/reference")
                        .cookie(accessTokenCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{ \"tmdbId\": \"550\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(
                        "Content-Type 'text/plain;charset=UTF-8' is not supported. Supported: application/json, application/*+json"));

        assertThat(contentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(post("/contents/reference")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"tmdbId\": \"550\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isUnauthorized());

        assertThat(contentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissing() throws Exception {
        mockMvc.perform(post("/contents/reference")
                        .cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"tmdbId\": \"550\", \"type\": \"MOVIE\" }"))
                .andExpect(status().isForbidden());

        assertThat(contentRepository.findAll()).isEmpty();
    }

    private MockHttpServletRequestBuilder referenceRequest(String body) {
        return post("/contents/reference")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, String email) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "Password123"
                }
                """.formatted(username, email);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}