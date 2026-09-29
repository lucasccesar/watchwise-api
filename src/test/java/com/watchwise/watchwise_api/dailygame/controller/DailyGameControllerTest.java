package com.watchwise.watchwise_api.dailygame.controller;

import com.watchwise.watchwise_api.common.exception.GlobalExceptionHandler;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAnswerDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameEpisodeOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSeasonOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameViewStatus;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameRankingService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameSearchService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DailyGameControllerTest {

    private static final UUID CURRENT_USER_ID = UUID.randomUUID();
    private static final DailyGameType GAME_TYPE = DailyGameType.MOVIE_BY_INFO;

    @Mock
    private DailyGameService dailyGameService;

    @Mock
    private DailyGameSearchService dailyGameSearchService;

    @Mock
    private DailyGameRankingService dailyGameRankingService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private DailyGameController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CURRENT_USER_ID, null, List.of()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getToday] Should Delegate With Current User - When Today Is Requested")
    void shouldDelegateTodayWithCurrentUser() throws Exception {
        when(dailyGameService.getToday(CURRENT_USER_ID))
                .thenReturn(new DailyGameTodayResponseDTO(LocalDate.of(2026, 9, 28), List.of()));

        mockMvc.perform(get("/games/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-09-28"))
                .andExpect(jsonPath("$.games").isEmpty());

        verify(dailyGameService).getToday(CURRENT_USER_ID);
    }

    @Test
    @DisplayName("[getDay] Should Delegate A Historical Date - When A Past Daily Set Is Requested")
    void shouldDelegateAHistoricalDateWhenAPastDailySetIsRequested() throws Exception {
        LocalDate historicalDate = LocalDate.of(2026, 9, 27);
        when(dailyGameService.getDay(CURRENT_USER_ID, historicalDate))
                .thenReturn(new DailyGameTodayResponseDTO(historicalDate, List.of()));

        mockMvc.perform(get("/games/{challengeDate}", historicalDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-09-27"));

        verify(dailyGameService).getDay(CURRENT_USER_ID, historicalDate);
    }

    @Test
    @DisplayName("[getGame] Should Delegate The Specific Game - When A Game Type Is Requested For Today")
    void shouldDelegateTheSpecificGameWhenAGameTypeIsRequestedForToday() throws Exception {
        when(dailyGameService.getGame(eq(CURRENT_USER_ID), any(LocalDate.class), eq(GAME_TYPE), eq(true)))
                .thenReturn(new DailyGameStateDTO(
                        GAME_TYPE, DailyGameTargetKind.MOVIE, 10, 0, 10, DailyGameViewStatus.NOT_PLAYED,
                        "/poster.jpg", List.of(), 0, null, null));

        mockMvc.perform(get("/games/{gameType}/today", GAME_TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_PLAYED"));

        verify(dailyGameService).getGame(eq(CURRENT_USER_ID), any(LocalDate.class), eq(GAME_TYPE), eq(true));
        verifyNoInteractions(dailyGameRankingService, dailyGameSearchService);
    }

    @Test
    @DisplayName("[getGame] Should Preserve The Major Roles Toggle - When A Historical Game Is Requested")
    void shouldPreserveTheMajorRolesToggleWhenAHistoricalGameIsRequested() throws Exception {
        LocalDate historicalDate = LocalDate.of(2026, 9, 27);
        when(dailyGameService.getGame(CURRENT_USER_ID, historicalDate, GAME_TYPE, false))
                .thenReturn(new DailyGameStateDTO(
                        GAME_TYPE, DailyGameTargetKind.MOVIE, 10, 0, 10, DailyGameViewStatus.NOT_PLAYED,
                        "/poster.jpg", List.of(), 0, null, null));

        mockMvc.perform(get("/games/{challengeDate}/{gameType}", historicalDate, GAME_TYPE)
                        .param("majorRoles", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_PLAYED"));

        verify(dailyGameService).getGame(CURRENT_USER_ID, historicalDate, GAME_TYPE, false);
    }

    @Test
    @DisplayName("[submitAttempt] Should Delegate And Return Response - When Attempt Is Valid")
    void shouldSubmitAttemptWithCurrentUserAndRateLimit() throws Exception {
        DailyGameAttemptResponseDTO response = attemptResponse();
        when(dailyGameService.submitAttempt(eq(CURRENT_USER_ID), eq(GAME_TYPE), any(DailyGameAttemptRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/games/{gameType}/attempt", GAME_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\":\"550\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameType").value(GAME_TYPE.name()))
                .andExpect(jsonPath("$.attemptsUsed").value(1))
                .andExpect(jsonPath("$.answer").doesNotExist());

        verify(requestThrottler).checkAllowed(
                "daily-game-attempt|" + CURRENT_USER_ID, 20, Duration.ofMinutes(5));
        verify(dailyGameService).submitAttempt(eq(CURRENT_USER_ID), eq(GAME_TYPE), any(DailyGameAttemptRequest.class));
    }

    @Test
    @DisplayName("[submitAttempt] Should Delegate A Historical Date - When A Past Game Is Played")
    void shouldDelegateAHistoricalDateWhenAPastGameIsPlayed() throws Exception {
        LocalDate historicalDate = LocalDate.of(2026, 9, 27);
        DailyGameAttemptResponseDTO response = attemptResponse();
        when(dailyGameService.submitAttempt(
                eq(CURRENT_USER_ID), eq(historicalDate), eq(GAME_TYPE), any(DailyGameAttemptRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/games/{challengeDate}/{gameType}/attempt", historicalDate, GAME_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tmdbId\":\"550\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameType").value(GAME_TYPE.name()));

        verify(dailyGameService).submitAttempt(
                eq(CURRENT_USER_ID), eq(historicalDate), eq(GAME_TYPE), any(DailyGameAttemptRequest.class));
    }

    @Test
    @DisplayName("[giveUp] Should Delegate And Apply The Attempt Limit - When The Current Game Is Abandoned")
    void shouldDelegateAndApplyTheAttemptLimitWhenTheCurrentGameIsAbandoned() throws Exception {
        when(dailyGameService.giveUp(CURRENT_USER_ID, GAME_TYPE)).thenReturn(attemptResponse());

        mockMvc.perform(post("/games/{gameType}/give-up", GAME_TYPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        verify(requestThrottler).checkAllowed(
                "daily-game-attempt|" + CURRENT_USER_ID, 20, Duration.ofMinutes(5));
        verify(dailyGameService).giveUp(CURRENT_USER_ID, GAME_TYPE);
    }

    @Test
    @DisplayName("[giveUp] Should Return An ApiError Conflict - When The Game Is Already Terminal")
    void shouldReturnAnApiErrorConflictWhenTheGameIsAlreadyTerminal() throws Exception {
        when(dailyGameService.giveUp(CURRENT_USER_ID, GAME_TYPE))
                .thenThrow(new ConflictException("Daily game attempt is already finished"));

        mockMvc.perform(post("/games/{gameType}/give-up", GAME_TYPE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.path").value("/games/MOVIE_BY_INFO/give-up"));
    }

    @Test
    @DisplayName("[search] Should Delegate All Search Routes - When Candidates And Episode Dropdowns Are Requested")
    void shouldDelegateAllSearchRoutesAndApplyOneSearchLimitPerRequest() throws Exception {
        DailyGameSearchResultDTO movie = new DailyGameSearchResultDTO(
                DailyGameTargetKind.MOVIE, "550", null, null, null, null, "Fight Club", "/fight.jpg", null);
        DailyGameSearchResultDTO series = new DailyGameSearchResultDTO(
                DailyGameTargetKind.SERIES, "1399", null, null, null, null, "Game of Thrones", "/got.jpg", null);
        DailyGameSeasonOptionDTO season = new DailyGameSeasonOptionDTO(1, "Season 1", 10);
        DailyGameEpisodeOptionDTO episode = new DailyGameEpisodeOptionDTO(
                "1399", 1, 1, "Winter Is Coming", LocalDate.of(2011, 4, 17));
        when(dailyGameSearchService.search(CURRENT_USER_ID, GAME_TYPE, "fight", 2, 5))
                .thenReturn(page(movie));
        when(dailyGameSearchService.searchEpisodeSeries(CURRENT_USER_ID, "game", 1, 10))
                .thenReturn(page(series));
        when(dailyGameSearchService.listEpisodeSeasons(CURRENT_USER_ID, "1399"))
                .thenReturn(List.of(season));
        when(dailyGameSearchService.listEpisodeEpisodes(CURRENT_USER_ID, "1399", 1))
                .thenReturn(List.of(episode));

        mockMvc.perform(get("/games/{gameType}/search", GAME_TYPE)
                        .param("q", "fight").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tmdbId").value("550"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5));
        mockMvc.perform(get("/games/EPISODE_BY_FRAME/search/series")
                        .param("q", "game").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tmdbId").value("1399"));
        mockMvc.perform(get("/games/EPISODE_BY_FRAME/search/seasons")
                        .param("seriesTmdbId", "1399"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seasonNumber").value(1))
                .andExpect(jsonPath("$[0].episodeCount").value(10))
                .andExpect(jsonPath("$[0].imageUrl").doesNotExist());
        mockMvc.perform(get("/games/EPISODE_BY_FRAME/search/episodes")
                        .param("seriesTmdbId", "1399").param("seasonNumber", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seriesTmdbId").value("1399"))
                .andExpect(jsonPath("$[0].episodeNumber").value(1))
                .andExpect(jsonPath("$[0].imageUrl").doesNotExist());

        verify(dailyGameSearchService).search(CURRENT_USER_ID, GAME_TYPE, "fight", 2, 5);
        verify(dailyGameSearchService).searchEpisodeSeries(CURRENT_USER_ID, "game", 1, 10);
        verify(dailyGameSearchService).listEpisodeSeasons(CURRENT_USER_ID, "1399");
        verify(dailyGameSearchService).listEpisodeEpisodes(CURRENT_USER_ID, "1399", 1);
        verify(requestThrottler, org.mockito.Mockito.times(4)).checkAllowed(
                eq("daily-game-search|" + CURRENT_USER_ID), eq(30), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("[search] Should Reject Generic Episode Route Without Throttling - When Episode By Frame Is Requested")
    void shouldRejectGenericEpisodeRouteWithoutThrottlingWhenEpisodeByFrameIsRequested() throws Exception {
        mockMvc.perform(get("/games/{gameType}/search", DailyGameType.EPISODE_BY_FRAME)
                        .param("q", "pilot"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Episode searches require the episode search endpoints"));

        verifyNoInteractions(requestThrottler, dailyGameSearchService);
    }

    @Test
    @DisplayName("[history and rankings] Should Wrap Every Page - When Paginated Data Is Returned")
    void shouldWrapHistoryAndRankingPages() throws Exception {
        DailyGameHistoryDTO history = new DailyGameHistoryDTO(
                LocalDate.of(2026, 9, 27), GAME_TYPE, DailyGameTargetKind.MOVIE, 10,
                DailyGameViewStatus.COMPLETED, 1, 10, LocalDateTime.now(), answer());
        DailyGameRankingEntryDTO ranking = new DailyGameRankingEntryDTO(
                1, CURRENT_USER_ID, "lucas", "/profile.jpg", 10, 1);
        when(dailyGameRankingService.getHistory(CURRENT_USER_ID, null, 2, 5)).thenReturn(page(history));
        when(dailyGameRankingService.getHistory(CURRENT_USER_ID, GAME_TYPE, 1, 5)).thenReturn(page(history));
        when(dailyGameRankingService.getGeneralRanking(1, 5)).thenReturn(page(ranking));
        when(dailyGameRankingService.getRanking(GAME_TYPE, 2, 5)).thenReturn(page(ranking));

        mockMvc.perform(get("/games/history").param("page", "2").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2));
        mockMvc.perform(get("/games/{gameType}/history", GAME_TYPE).param("page", "1").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].gameType").value(GAME_TYPE.name()));
        mockMvc.perform(get("/games/rankings/general").param("page", "1").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].username").value("lucas"))
                .andExpect(jsonPath("$.content[0].gamesPlayed").value(1));
        mockMvc.perform(get("/games/{gameType}/ranking", GAME_TYPE).param("page", "2").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6));

        verify(dailyGameRankingService).getHistory(CURRENT_USER_ID, null, 2, 5);
        verify(dailyGameRankingService).getHistory(CURRENT_USER_ID, GAME_TYPE, 1, 5);
        verify(dailyGameRankingService).getGeneralRanking(1, 5);
        verify(dailyGameRankingService).getRanking(GAME_TYPE, 2, 5);
        verifyNoInteractions(requestThrottler);
    }

    private PageImpl<DailyGameSearchResultDTO> page(DailyGameSearchResultDTO value) {
        return new PageImpl<>(List.of(value), PageRequest.of(1, 5), 6);
    }

    private PageImpl<DailyGameHistoryDTO> page(DailyGameHistoryDTO value) {
        return new PageImpl<>(List.of(value), PageRequest.of(1, 5), 6);
    }

    private PageImpl<DailyGameRankingEntryDTO> page(DailyGameRankingEntryDTO value) {
        return new PageImpl<>(List.of(value), PageRequest.of(1, 5), 6);
    }

    private DailyGameAttemptResponseDTO attemptResponse() {
        return new DailyGameAttemptResponseDTO(
                GAME_TYPE, DailyGameTargetKind.MOVIE, 10, 1, 9, DailyGameViewStatus.IN_PROGRESS,
                "/hint.jpg", List.of(), 0, null, null);
    }

    private DailyGameAnswerDTO answer() {
        return new DailyGameAnswerDTO(DailyGameTargetKind.MOVIE, "550", null, null, null, null,
                "Fight Club", "/fight.jpg");
    }
}
