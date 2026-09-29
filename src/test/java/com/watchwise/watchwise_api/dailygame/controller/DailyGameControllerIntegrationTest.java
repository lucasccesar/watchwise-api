package com.watchwise.watchwise_api.dailygame.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.DailyGamesUnavailableException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameRankingService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameSearchService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
class DailyGameControllerIntegrationTest {

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
    private DailyGameService dailyGameService;

    @MockitoBean
    private DailyGameSearchService dailyGameSearchService;

    @MockitoBean
    private DailyGameRankingService dailyGameRankingService;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        reset(dailyGameService, dailyGameSearchService, dailyGameRankingService);
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    @DisplayName("[getToday] Should Return Authenticated State Without Answer - When The Game Is In Progress")
    void shouldReturnAuthenticatedStateWithoutAnswerWhenGameIsInProgress() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerstate");
        DailyGameStateDTO game = new DailyGameStateDTO(
                DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, 10, 1, 9,
                DailyGameViewStatus.IN_PROGRESS, "/hint.jpg", List.of(), 0, null, null, false, false);
        when(dailyGameService.getToday(user.id()))
                .thenReturn(new DailyGameTodayResponseDTO(LocalDate.of(2026, 9, 28), List.of(game)));

        mockMvc.perform(get("/games/today").cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.games[0].answer").doesNotExist());

        verify(dailyGameService).getToday(user.id());
    }

    @Test
    @DisplayName("[getGame] Should Resolve The Specific Route - When A Game Type Is Requested After The Aggregate Route")
    void shouldResolveTheSpecificRouteWhenAGameTypeIsRequestedAfterTheAggregateRoute() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerspecific");
        DailyGameStateDTO game = new DailyGameStateDTO(
                DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, 10, 0, 10,
                DailyGameViewStatus.NOT_PLAYED, "/hint.jpg", List.of(), 0, null, null);
        when(dailyGameService.getGameToday(user.id(), DailyGameType.MOVIE_BY_INFO, true)).thenReturn(game);

        mockMvc.perform(get("/games/{gameType}/today", DailyGameType.MOVIE_BY_INFO)
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_PLAYED"));

        verify(dailyGameService).getGameToday(user.id(), DailyGameType.MOVIE_BY_INFO, true);
        verify(dailyGameService, never()).getToday(user.id());
    }

    @Test
    @DisplayName("[getToday] Should Return Unauthorized - When Access Token Is Missing")
    void shouldReturnUnauthorizedWhenAccessTokenIsMissing() throws Exception {
        mockMvc.perform(get("/games/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(dailyGameService);
    }

    @Test
    @DisplayName("[submitAttempt] Should Return Forbidden - When Csrf Header Is Missing")
    void shouldReturnForbiddenWhenCsrfHeaderIsMissing() throws Exception {
        RegisteredUser user = registerUser("dailycontrollercsrf");

        mockMvc.perform(post("/games/{gameType}/attempt", DailyGameType.MOVIE_BY_INFO)
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\":\"550\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(dailyGameService);
    }

    @Test
    @DisplayName("[giveUp] Should Return Forbidden - When Csrf Header Is Missing")
    void shouldReturnForbiddenWhenGiveUpCsrfHeaderIsMissing() throws Exception {
        RegisteredUser user = registerUser("dailycontrollergiveupcsrf");

        mockMvc.perform(post("/games/{gameType}/give-up", DailyGameType.MOVIE_BY_INFO)
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(dailyGameService);
    }

    @Test
    @DisplayName("[search] Should Return Unauthorized - When Episode Dropdown Access Token Is Missing")
    void shouldReturnUnauthorizedWhenEpisodeDropdownAccessTokenIsMissing() throws Exception {
        mockMvc.perform(get("/games/EPISODE_BY_FRAME/series/1399/seasons"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(dailyGameSearchService);
    }

    @Test
    @DisplayName("[submitAttempt] Should Return Ok - When Access And Csrf Cookies Are Valid")
    void shouldReturnOkWhenAccessAndCsrfCookiesAreValid() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerattempt");
        DailyGameAttemptResponseDTO response = new DailyGameAttemptResponseDTO(
                DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, 10, 1, 9,
                DailyGameViewStatus.IN_PROGRESS, "/hint.jpg", List.of(), 0, null, null, false, false);
        when(dailyGameService.submitAttempt(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), any(), eq(true)))
                .thenReturn(response);

        mockMvc.perform(attemptRequest(user, DailyGameType.MOVIE_BY_INFO, "{\"tmdbId\":\"550\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptsUsed").value(1))
                .andExpect(jsonPath("$.answer").doesNotExist());

        verify(dailyGameService).submitAttempt(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), any(), eq(true));
    }

    @Test
    @DisplayName("[submitAttempt] Should Bind Sharing Toggle And Return Shared Terminal State - When Toggle Is Enabled")
    void shouldBindSharingToggleAndReturnSharedTerminalStateWhenToggleIsEnabled() throws Exception {
        RegisteredUser user = registerUser("dailycontrollershare");
        DailyGameAttemptResponseDTO response = new DailyGameAttemptResponseDTO(
                DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, 10, 1, 9,
                DailyGameViewStatus.COMPLETED, "/hint.jpg", List.of(), 10, LocalDateTime.now(),
                answer(), true, true);
        when(dailyGameService.submitAttempt(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), any()))
                .thenReturn(response);

        mockMvc.perform(attemptRequest(
                        user, DailyGameType.MOVIE_BY_INFO, "{\"tmdbId\":\"550\",\"shareOnCompletion\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.shareOnCompletion").value(true))
                .andExpect(jsonPath("$.sharedToFeed").value(true))
                .andExpect(jsonPath("$.answer.tmdbId").value("550"));

        verify(dailyGameService).submitAttempt(
                eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO),
                argThat(request -> Boolean.TRUE.equals(request.shareOnCompletion())));
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Game Type Is Invalid")
    void shouldReturnBadRequestApiErrorWhenGameTypeIsInvalid() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerenum");

        mockMvc.perform(get("/games/UNKNOWN/search")
                        .param("q", "fight")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/games/UNKNOWN/search"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(dailyGameSearchService);
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Episode Series Identifier Is Invalid")
    void shouldReturnBadRequestApiErrorWhenEpisodeSeriesIdentifierIsInvalid() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerepisodeseriesid");
        when(dailyGameSearchService.listEpisodeSeasons(user.id(), "abc"))
                .thenThrow(new BadRequestException("seriesTmdbId must be a positive numeric identifier"));

        mockMvc.perform(get("/games/EPISODE_BY_FRAME/series/abc/seasons").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("seriesTmdbId must be a positive numeric identifier"))
                .andExpect(jsonPath("$.path").value("/games/EPISODE_BY_FRAME/series/abc/seasons"));

        verify(dailyGameSearchService).listEpisodeSeasons(user.id(), "abc");
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Episode Season Coordinate Is Invalid")
    void shouldReturnBadRequestApiErrorWhenEpisodeSeasonCoordinateIsInvalid() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerepisodeseason");
        when(dailyGameSearchService.listEpisodeEpisodes(user.id(), "1399", 0))
                .thenThrow(new BadRequestException("seasonNumber must be a positive integer"));

        mockMvc.perform(get("/games/EPISODE_BY_FRAME/series/1399/seasons/0/episodes")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("seasonNumber must be a positive integer"))
                .andExpect(jsonPath("$.path").value("/games/EPISODE_BY_FRAME/series/1399/seasons/0/episodes"));

        verify(dailyGameSearchService).listEpisodeEpisodes(user.id(), "1399", 0);
    }

    @Test
    @DisplayName("[search] Should Return NotFound ApiError - When The Selected Episode Series Does Not Exist")
    void shouldReturnNotFoundApiErrorWhenTheSelectedEpisodeSeriesDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerepisodeseriesmissing");
        when(dailyGameSearchService.listEpisodeSeasons(user.id(), "1399"))
                .thenThrow(new NotFoundException("No series found on TMDB for the given id"));

        mockMvc.perform(get("/games/EPISODE_BY_FRAME/series/1399/seasons")
                        .cookie(user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No series found on TMDB for the given id"))
                .andExpect(jsonPath("$.path").value("/games/EPISODE_BY_FRAME/series/1399/seasons"));

        verify(dailyGameSearchService).listEpisodeSeasons(user.id(), "1399");
    }

    @Test
    @DisplayName("[submitAttempt] Should Return BadRequest ApiError - When Candidate Identifiers Are Invalid")
    void shouldReturnBadRequestApiErrorWhenCandidateIdentifiersAreInvalid() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerbody");

        mockMvc.perform(attemptRequest(user, DailyGameType.MOVIE_BY_INFO, "{\"tmdbId\":\"not-numeric\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation Failed"))
                .andExpect(jsonPath("$.path").value("/games/MOVIE_BY_INFO/attempt"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(dailyGameService);
    }

    @Test
    @DisplayName("[search] Should Return BadRequest ApiError - When Page Is Invalid")
    void shouldReturnBadRequestApiErrorWhenPageIsInvalid() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerpage");
        when(dailyGameSearchService.search(user.id(), DailyGameType.MOVIE_BY_INFO, "fight", -1, 20))
                .thenThrow(new BadRequestException("Page number must be greater than or equal to 0"));

        mockMvc.perform(get("/games/{gameType}/search", DailyGameType.MOVIE_BY_INFO)
                        .param("q", "fight").param("page", "-1").param("size", "20")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Page number must be greater than or equal to 0"))
                .andExpect(jsonPath("$.path").value("/games/MOVIE_BY_INFO/search"));

        verify(dailyGameSearchService).search(user.id(), DailyGameType.MOVIE_BY_INFO, "fight", -1, 20);
    }

    @Test
    @DisplayName("[submitAttempt] Should Return Conflict - When The Game Is Already Completed")
    void shouldReturnConflictWhenTheGameIsAlreadyCompleted() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerconflict");
        when(dailyGameService.submitAttempt(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), any(), eq(true)))
                .thenThrow(new ConflictException("Daily game attempt is already finished"));

        mockMvc.perform(attemptRequest(user, DailyGameType.MOVIE_BY_INFO, "{\"tmdbId\":\"550\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/games/MOVIE_BY_INFO/attempt"));
    }

    @Test
    @DisplayName("[giveUp] Should Return Conflict ApiError - When The Game Is Already Completed")
    void shouldReturnConflictApiErrorWhenGiveUpGameIsAlreadyCompleted() throws Exception {
        RegisteredUser user = registerUser("dailycontrollergiveupconflict");
        when(dailyGameService.giveUp(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO)))
                .thenThrow(new ConflictException("Daily game attempt is already finished"));

        mockMvc.perform(post("/games/{gameType}/give-up", DailyGameType.MOVIE_BY_INFO)
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/games/MOVIE_BY_INFO/give-up"));
    }

    @Test
    @DisplayName("[getToday] Should Return ServiceUnavailable - When The Daily Set Is Missing")
    void shouldReturnServiceUnavailableWhenTheDailySetIsMissing() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerunavailable");
        when(dailyGameService.getToday(user.id())).thenThrow(new DailyGamesUnavailableException());

        mockMvc.perform(get("/games/today").cookie(user.accessToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Daily games are temporarily unavailable"))
                .andExpect(jsonPath("$.path").value("/games/today"));
    }

    @Test
    @DisplayName("[search] Should Return BadGateway - When TMDB Is Unavailable")
    void shouldReturnBadGatewayWhenTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("dailycontrollertmdb");
        when(dailyGameSearchService.search(user.id(), DailyGameType.MOVIE_BY_INFO, "fight", null, null))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/games/{gameType}/search", DailyGameType.MOVIE_BY_INFO)
                        .param("q", "fight").cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.path").value("/games/MOVIE_BY_INFO/search"));
    }

    @Test
    @DisplayName("[search] Should Return BadGateway - When Episode Dropdown TMDB Is Unavailable")
    void shouldReturnBadGatewayWhenEpisodeDropdownTmdbIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerepisodetmdb");
        when(dailyGameSearchService.listEpisodeSeasons(user.id(), "1399"))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        mockMvc.perform(get("/games/EPISODE_BY_FRAME/series/1399/seasons").cookie(user.accessToken()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"))
                .andExpect(jsonPath("$.path").value("/games/EPISODE_BY_FRAME/series/1399/seasons"));
    }

    @Test
    @DisplayName("[history and ranking] Should Return Page Envelopes - When Requests Are Authenticated")
    void shouldReturnHistoryAndRankingPageEnvelopesWhenRequestsAreAuthenticated() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerpages");
        DailyGameHistoryDTO history = new DailyGameHistoryDTO(
                LocalDate.of(2026, 9, 27), DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE,
                10, DailyGameViewStatus.COMPLETED, 1, 10, LocalDateTime.now(), answer(), false);
        DailyGameRankingEntryDTO ranking = new DailyGameRankingEntryDTO(
                1, user.id(), "dailycontrollerpages", null, 10, 1);
        when(dailyGameRankingService.getHistory(user.id(), null, 1, 20))
                .thenReturn(new PageImpl<>(List.of(history), PageRequest.of(0, 20), 1));
        when(dailyGameRankingService.getHistory(user.id(), DailyGameType.MOVIE_BY_INFO, 1, 20))
                .thenReturn(new PageImpl<>(List.of(history), PageRequest.of(0, 20), 1));
        when(dailyGameRankingService.getGeneralRanking(1, 20))
                .thenReturn(new PageImpl<>(List.of(ranking), PageRequest.of(0, 20), 1));
        when(dailyGameRankingService.getRanking(DailyGameType.MOVIE_BY_INFO, 1, 20))
                .thenReturn(new PageImpl<>(List.of(ranking), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/games/history").param("page", "1").param("size", "20")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content[0].answer.tmdbId").value("550"));
        mockMvc.perform(get("/games/{gameType}/history", DailyGameType.MOVIE_BY_INFO)
                        .param("page", "1").param("size", "20").cookie(user.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].gameType").value("MOVIE_BY_INFO"));
        mockMvc.perform(get("/games/rankings/general").param("page", "1").param("size", "20")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].username").value("dailycontrollerpages"))
                .andExpect(jsonPath("$.content[0].gamesPlayed").value(1));
        mockMvc.perform(get("/games/{gameType}/ranking", DailyGameType.MOVIE_BY_INFO)
                        .param("page", "1").param("size", "20").cookie(user.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("[search] Should Return TooManyRequests - When Search Limit Is Exceeded")
    void shouldReturnTooManyRequestsWhenSearchLimitIsExceeded() throws Exception {
        RegisteredUser user = registerUser("dailycontrollersearchlimit");
        when(dailyGameSearchService.search(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), eq("fight"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        for (int attempt = 0; attempt < 30; attempt++) {
            mockMvc.perform(get("/games/{gameType}/search", DailyGameType.MOVIE_BY_INFO)
                            .param("q", "fight").cookie(user.accessToken()))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/games/{gameType}/search", DailyGameType.MOVIE_BY_INFO)
                        .param("q", "fight").cookie(user.accessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verify(dailyGameSearchService, times(30))
                .search(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), eq("fight"), any(), any());
    }

    @Test
    @DisplayName("[submitAttempt] Should Return TooManyRequests - When Attempt Limit Is Exceeded")
    void shouldReturnTooManyRequestsWhenAttemptLimitIsExceeded() throws Exception {
        RegisteredUser user = registerUser("dailycontrollerattemptlimit");
        DailyGameAttemptResponseDTO response = new DailyGameAttemptResponseDTO(
                DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, 10, 1, 9,
                DailyGameViewStatus.IN_PROGRESS, "/hint.jpg", List.of(), 0, null, null, false, false);
        when(dailyGameService.submitAttempt(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), any(), eq(true)))
                .thenReturn(response);

        for (int attempt = 0; attempt < 20; attempt++) {
            mockMvc.perform(attemptRequest(user, DailyGameType.MOVIE_BY_INFO, "{\"tmdbId\":\"550\"}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(attemptRequest(user, DailyGameType.MOVIE_BY_INFO, "{\"tmdbId\":\"550\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        verify(dailyGameService, times(20))
                .submitAttempt(eq(user.id()), eq(DailyGameType.MOVIE_BY_INFO), any(), eq(true));
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessToken = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        assertThat(csrfToken).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessToken, csrfToken);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "username": "%s",
                            "email": "%s@email.com",
                            "password": "Password123"
                        }
                        """.formatted(username, username));
    }

    private MockHttpServletRequestBuilder attemptRequest(
            RegisteredUser user, DailyGameType gameType, String body) {
        return post("/games/{gameType}/attempt", gameType)
                .cookie(user.accessToken(), user.csrfToken())
                .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private DailyGameAnswerDTO answer() {
        return new DailyGameAnswerDTO(
                DailyGameTargetKind.MOVIE, "550", null, null, null, null, "Fight Club", "/fight.jpg");
    }
}
