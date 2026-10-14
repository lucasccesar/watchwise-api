package com.watchwise.watchwise_api.dailygame.controller;

import com.watchwise.watchwise_api.common.exception.GlobalExceptionHandler;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaResponseDTO;
import com.watchwise.watchwise_api.dailygame.service.DailyGameMediaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DailyGameMediaControllerIntegrationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void servesAuthenticatedImageWithPrivateNoStoreAndDedicatedThrottle() throws Exception {
        UUID user = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        DailyGameMediaService service = mock(DailyGameMediaService.class);
        RequestThrottler throttler = mock(RequestThrottler.class);
        when(service.fetch(user, "opaque-token")).thenReturn(new DailyGameMediaResponseDTO(new byte[] {1, 2}, MediaType.IMAGE_PNG));
        var mvc = MockMvcBuilders.standaloneSetup(new DailyGameMediaController(service, throttler))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/games/play/media/opaque-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[] {1, 2}));
        verify(throttler).checkAllowed("daily-game-media|" + user, 120, Duration.ofMinutes(5));
    }
}
