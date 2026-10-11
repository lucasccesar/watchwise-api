package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;
import com.watchwise.watchwise_api.content.service.ContentSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ContentSelectionController {

    private final ContentSelectionService contentSelectionService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.search.max-requests}")
    private int searchMaxRequests;

    @Value("${app.rate-limit.search.window-minutes}")
    private long searchWindowMinutes;

    @GetMapping("/contents/selection/series/{seriesTmdbId}/seasons")
    public ResponseEntity<List<ContentSeasonSelectionDTO>> listSeasons(
            @PathVariable String seriesTmdbId,
            @RequestParam(defaultValue = "false") boolean includeSpecials) {
        UUID viewerId = getCurrentUserId();
        checkRateLimit(viewerId);
        return ResponseEntity.ok(contentSelectionService.listSeasons(viewerId, seriesTmdbId, includeSpecials));
    }

    @GetMapping("/contents/selection/series/{seriesTmdbId}/seasons/{seasonNumber}/episodes")
    public ResponseEntity<List<ContentEpisodeSelectionDTO>> listEpisodes(
            @PathVariable String seriesTmdbId,
            @PathVariable int seasonNumber,
            @RequestParam(defaultValue = "true") boolean includeUnreleased) {
        UUID viewerId = getCurrentUserId();
        checkRateLimit(viewerId);
        return ResponseEntity.ok(
                contentSelectionService.listEpisodes(viewerId, seriesTmdbId, seasonNumber, includeUnreleased));
    }

    private void checkRateLimit(UUID viewerId) {
        requestThrottler.checkAllowed(
                "content-selection|" + viewerId,
                searchMaxRequests,
                Duration.ofMinutes(searchWindowMinutes));
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
