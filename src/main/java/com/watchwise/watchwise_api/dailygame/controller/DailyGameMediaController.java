package com.watchwise.watchwise_api.dailygame.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaResponseDTO;
import com.watchwise.watchwise_api.dailygame.service.DailyGameMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DailyGameMediaController {
    private final DailyGameMediaService mediaService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.daily-game-media.max-requests:120}")
    private int maxRequests = 120;
    @Value("${app.rate-limit.daily-game-media.window-minutes:5}")
    private long windowMinutes = 5;

    @GetMapping("/games/play/media/{grantToken}")
    public ResponseEntity<byte[]> fetch(@PathVariable String grantToken) {
        UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        requestThrottler.checkAllowed("daily-game-media|" + userId, maxRequests, Duration.ofMinutes(windowMinutes));
        DailyGameMediaResponseDTO image = mediaService.fetch(userId, grantToken);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .contentType(image.mediaType()).body(image.body());
    }
}
