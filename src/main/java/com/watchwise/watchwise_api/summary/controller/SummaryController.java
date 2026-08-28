package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.service.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryService summaryService;

    @GetMapping("/users/{userId}/summary")
    public ResponseEntity<SummaryResponseDTO> getSummary(
            @PathVariable UUID userId,
            @RequestParam(required = false) ContentType type
    ) {
        SummaryResponseDTO summary = summaryService.getSummary(getCurrentUserId(), userId, type);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/users/{userId}/summary/home")
    public ResponseEntity<HomeSummaryResponseDTO> getHomeSummary(@PathVariable UUID userId) {
        HomeSummaryResponseDTO response = summaryService.getHomeSummary(getCurrentUserId(), userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/{userId}/summary/month")
    public ResponseEntity<MonthInReviewResponseDTO> getMonthInReview(
            @PathVariable UUID userId,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) String month
    ) {
        MonthInReviewResponseDTO response = summaryService.getMonthInReview(
                getCurrentUserId(), userId, type, parseYearMonth(month));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/{userId}/summary/year")
    public ResponseEntity<YearInReviewResponseDTO> getYearInReview(
            @PathVariable UUID userId,
            @RequestParam(required = false) ContentType type,
            @RequestParam(required = false) Integer year
    ) {
        YearInReviewResponseDTO response = summaryService.getYearInReview(getCurrentUserId(), userId, type, year);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/{userId}/summary/all-time")
    public ResponseEntity<AllTimeStatsResponseDTO> getAllTimeStats(@PathVariable UUID userId) {
        AllTimeStatsResponseDTO response = summaryService.getAllTimeStats(getCurrentUserId(), userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/{userId}/series/{seriesTmdbId}/episode-ratings")
    public ResponseEntity<EpisodeRatingsGridResponseDTO> getEpisodeRatingsGrid(
            @PathVariable UUID userId,
            @PathVariable String seriesTmdbId
    ) {
        EpisodeRatingsGridResponseDTO response = summaryService.getEpisodeRatingsGrid(getCurrentUserId(), userId, seriesTmdbId);
        return ResponseEntity.ok(response);
    }

    private YearMonth parseYearMonth(String month) {
        if (month == null) {
            return null;
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeException e) {
            throw new BadRequestException("month must be in YYYY-MM format");
        }
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
