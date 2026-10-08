package com.watchwise.watchwise_api.trending.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingService;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TrendingController {

    private final TrendingService trendingService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.trending.max-requests}")
    private int trendingMaxRequests;

    @Value("${app.rate-limit.trending.window-minutes}")
    private long trendingWindowMinutes;

    @GetMapping("/trending")
    public ResponseEntity<TrendingResponseDTO> getTrending(
            @RequestParam String timeWindow,
            @RequestParam(defaultValue = "12") int size
    ) {
        TrendingTimeWindow parsedTimeWindow = parseTimeWindow(timeWindow);
        validateSize(size);

        UUID currentUserId = getCurrentUserId();
        requestThrottler.checkAllowed(
                "trending|" + currentUserId,
                trendingMaxRequests,
                Duration.ofMinutes(trendingWindowMinutes));

        return ResponseEntity.ok(trendingService.getTrending(currentUserId, parsedTimeWindow, size));
    }

    @GetMapping("/trending/{type}")
    public ResponseEntity<TrendingResponseDTO> getTrendingSection(
            @PathVariable String type,
            @RequestParam String timeWindow,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        MovieOrSeriesType parsedType = parseType(type);
        TrendingTimeWindow parsedTimeWindow = parseTimeWindow(timeWindow);
        validatePage(page);
        validateSize(size);

        UUID currentUserId = getCurrentUserId();
        requestThrottler.checkAllowed(
                "trending|" + currentUserId,
                trendingMaxRequests,
                Duration.ofMinutes(trendingWindowMinutes));

        return ResponseEntity.ok(trendingService.getTrendingSection(
                currentUserId, parsedType, parsedTimeWindow, page, size));
    }

    private TrendingTimeWindow parseTimeWindow(String timeWindow) {
        for (TrendingTimeWindow candidate : TrendingTimeWindow.values()) {
            if (candidate.value().equals(timeWindow)) {
                return candidate;
            }
        }
        throw new BadRequestException("timeWindow must be one of: day, week");
    }

    private void validateSize(int size) {
        if (size != 12 && size != 21) {
            throw new BadRequestException("size must be one of: 12, 21");
        }
    }

    private void validatePage(int page) {
        if (page < 1) {
            throw new BadRequestException("page must be greater than 0");
        }
    }

    private MovieOrSeriesType parseType(String type) {
        for (MovieOrSeriesType candidate : MovieOrSeriesType.values()) {
            if (candidate.name().equalsIgnoreCase(type)) {
                return candidate;
            }
        }
        throw new BadRequestException("type must be one of: movie, series");
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
