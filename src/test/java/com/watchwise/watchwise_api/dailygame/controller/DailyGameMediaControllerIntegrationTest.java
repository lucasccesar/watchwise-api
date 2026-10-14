package com.watchwise.watchwise_api.dailygame.controller;

import com.watchwise.watchwise_api.common.exception.GlobalExceptionHandler;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageBytes;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageTransport;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameMediaResponseDTO;
import com.watchwise.watchwise_api.dailygame.media.DailyGameImageVariant;
import com.watchwise.watchwise_api.dailygame.media.DailyGameMediaSlot;
import com.watchwise.watchwise_api.dailygame.service.DailyGameMediaService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DailyGameMediaControllerIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DailyGameMediaService realMediaService;
    @MockitoBean private TmdbClient tmdbClient;
    @MockitoBean private TmdbImageTransport imageTransport;

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

    @Test
    void jwtCookieRouteRejectsAnonymousAndForeignUsersButServesOwner() throws Exception {
        Cookie ownerCookie = register("mediaowner");
        Cookie foreignCookie = register("mediaforeign");
        UUID ownerId = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("mediaowner", "mediaowner")
                .orElseThrow().getId();
        byte[] source = png();
        when(tmdbClient.getImageBytes("/frame.png")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbImageBytes(source, MediaType.IMAGE_PNG)));
        String url = realMediaService.issue(ownerId, UUID.randomUUID(), DailyGameMediaSlot.FRAME_0,
                DailyGameImageVariant.ORIGINAL, "/frame.png").url();

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mockMvc.perform(get(url).cookie(foreignCookie)).andExpect(status().isNotFound());
        verify(tmdbClient, times(1)).getImageBytes("/frame.png");
    }

    private Cookie register(String username) throws Exception {
        var result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123"}
                                """.formatted(username, username, username)))
                .andExpect(status().isCreated()).andReturn();
        return result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
