package com.watchwise.watchwise_api.dailygame.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptRequest;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameAttemptResponseDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameEpisodeOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameHistoryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameRankingEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSearchResultDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameSeasonOptionDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameStateDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameTodayResponseDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameRankingService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameSearchService;
import com.watchwise.watchwise_api.dailygame.service.DailyGameService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DailyGameController {

    private final DailyGameService dailyGameService;
    private final DailyGameSearchService dailyGameSearchService;
    private final DailyGameRankingService dailyGameRankingService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.daily-game-search.max-requests:30}")
    private int searchMaxRequests = 30;

    @Value("${app.rate-limit.daily-game-search.window-minutes:5}")
    private long searchWindowMinutes = 5;

    @Value("${app.rate-limit.daily-game-attempt.max-requests:20}")
    private int attemptMaxRequests = 20;

    @Value("${app.rate-limit.daily-game-attempt.window-minutes:5}")
    private long attemptWindowMinutes = 5;

    @GetMapping("/games/today")
    public ResponseEntity<DailyGameTodayResponseDTO> getToday() {
        return ResponseEntity.ok(dailyGameService.getToday(currentUserId()));
    }

    @GetMapping("/games/{challengeDate}")
    public ResponseEntity<DailyGameTodayResponseDTO> getDay(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate challengeDate) {
        return ResponseEntity.ok(dailyGameService.getDay(currentUserId(), challengeDate));
    }

    @GetMapping("/games/{gameType}/today")
    public ResponseEntity<DailyGameStateDTO> getGameToday(
        @PathVariable("gameType") DailyGameType gameType,
            @RequestParam(value = "majorRoles", defaultValue = "true") boolean majorRoles) {
        return ResponseEntity.ok(dailyGameService.getGame(
                currentUserId(), LocalDate.now(ZoneOffset.UTC), gameType, majorRoles));
    }

    @GetMapping("/games/{challengeDate}/{gameType}")
    public ResponseEntity<DailyGameStateDTO> getGame(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate challengeDate,
            @PathVariable("gameType") DailyGameType gameType,
            @RequestParam(value = "majorRoles", defaultValue = "true") boolean majorRoles) {
        return ResponseEntity.ok(dailyGameService.getGame(currentUserId(), challengeDate, gameType, majorRoles));
    }

    @GetMapping("/games/{gameType}/search")
    public ResponseEntity<PageResponseDTO<DailyGameSearchResultDTO>> search(
            @PathVariable("gameType") DailyGameType gameType,
            @RequestParam("q") String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        if (gameType == DailyGameType.EPISODE_BY_FRAME) {
            throw new BadRequestException("Episode searches require the episode search endpoints");
        }
        UUID userId = currentUserId();
        throttleSearch(userId);
        Page<DailyGameSearchResultDTO> results = dailyGameSearchService.search(userId, gameType, query, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(results));
    }

    @GetMapping("/games/EPISODE_BY_FRAME/search/series")
    public ResponseEntity<PageResponseDTO<DailyGameSearchResultDTO>> searchEpisodeSeries(
            @RequestParam("q") String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        UUID userId = currentUserId();
        throttleSearch(userId);
        Page<DailyGameSearchResultDTO> results = dailyGameSearchService.searchEpisodeSeries(userId, query, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(results));
    }

    @GetMapping("/games/EPISODE_BY_FRAME/search/seasons")
    public ResponseEntity<List<DailyGameSeasonOptionDTO>> listEpisodeSeasons(
            @RequestParam("seriesTmdbId") String seriesTmdbId) {
        UUID userId = currentUserId();
        throttleSearch(userId);
        return ResponseEntity.ok(dailyGameSearchService.listEpisodeSeasons(userId, seriesTmdbId));
    }

    @GetMapping("/games/EPISODE_BY_FRAME/search/episodes")
    public ResponseEntity<List<DailyGameEpisodeOptionDTO>> listEpisodeEpisodes(
            @RequestParam("seriesTmdbId") String seriesTmdbId,
            @RequestParam("seasonNumber") Integer seasonNumber) {
        UUID userId = currentUserId();
        throttleSearch(userId);
        return ResponseEntity.ok(dailyGameSearchService.listEpisodeEpisodes(
                userId, seriesTmdbId, seasonNumber));
    }

    @PostMapping("/games/{gameType}/attempt")
    public ResponseEntity<DailyGameAttemptResponseDTO> submitAttempt(
            @PathVariable("gameType") DailyGameType gameType,
            @RequestParam(value = "majorRoles", defaultValue = "true") boolean majorRoles,
            @Valid @RequestBody DailyGameAttemptRequest request) {
        UUID userId = currentUserId();
        throttleAttempt(userId);
        return ResponseEntity.ok(dailyGameService.submitAttempt(userId, gameType, request, majorRoles));
    }

    @PostMapping("/games/{challengeDate}/{gameType}/attempt")
    public ResponseEntity<DailyGameAttemptResponseDTO> submitHistoricalAttempt(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate challengeDate,
            @PathVariable("gameType") DailyGameType gameType,
            @RequestParam(value = "majorRoles", defaultValue = "true") boolean majorRoles,
            @Valid @RequestBody DailyGameAttemptRequest request) {
        UUID userId = currentUserId();
        throttleAttempt(userId);
        return ResponseEntity.ok(dailyGameService.submitAttempt(userId, challengeDate, gameType, request, majorRoles));
    }

    @PostMapping("/games/{gameType}/give-up")
    public ResponseEntity<DailyGameAttemptResponseDTO> giveUp(
            @PathVariable("gameType") DailyGameType gameType) {
        UUID userId = currentUserId();
        throttleAttempt(userId);
        return ResponseEntity.ok(dailyGameService.giveUp(userId, gameType));
    }

    @PostMapping("/games/{challengeDate}/{gameType}/give-up")
    public ResponseEntity<DailyGameAttemptResponseDTO> giveUpHistorical(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate challengeDate,
            @PathVariable("gameType") DailyGameType gameType) {
        UUID userId = currentUserId();
        throttleAttempt(userId);
        return ResponseEntity.ok(dailyGameService.giveUp(userId, challengeDate, gameType));
    }

    @GetMapping("/games/history")
    public ResponseEntity<PageResponseDTO<DailyGameHistoryDTO>> getHistory(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Page<DailyGameHistoryDTO> history = dailyGameRankingService.getHistory(currentUserId(), null, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(history));
    }

    @GetMapping("/games/{gameType}/history")
    public ResponseEntity<PageResponseDTO<DailyGameHistoryDTO>> getHistoryByType(
            @PathVariable("gameType") DailyGameType gameType,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Page<DailyGameHistoryDTO> history = dailyGameRankingService.getHistory(
                currentUserId(), gameType, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(history));
    }

    @GetMapping("/games/rankings/general")
    public ResponseEntity<PageResponseDTO<DailyGameRankingEntryDTO>> getGeneralRanking(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Page<DailyGameRankingEntryDTO> ranking = dailyGameRankingService.getGeneralRanking(page, size);
        return ResponseEntity.ok(PageResponseDTO.of(ranking));
    }

    @GetMapping("/games/{gameType}/ranking")
    public ResponseEntity<PageResponseDTO<DailyGameRankingEntryDTO>> getRanking(
            @PathVariable("gameType") DailyGameType gameType,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        Page<DailyGameRankingEntryDTO> ranking = dailyGameRankingService.getRanking(gameType, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(ranking));
    }

    private void throttleSearch(UUID userId) {
        requestThrottler.checkAllowed(
                "daily-game-search|" + userId,
                searchMaxRequests,
                Duration.ofMinutes(searchWindowMinutes));
    }

    private void throttleAttempt(UUID userId) {
        requestThrottler.checkAllowed(
                "daily-game-attempt|" + userId,
                attemptMaxRequests,
                Duration.ofMinutes(attemptWindowMinutes));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
