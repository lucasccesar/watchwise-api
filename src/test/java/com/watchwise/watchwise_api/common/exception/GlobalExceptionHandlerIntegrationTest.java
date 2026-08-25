package com.watchwise.watchwise_api.common.exception;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GlobalExceptionHandlerIntegrationTest {

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
    private JwtService jwtService;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    @DisplayName("[handleUnexpectedException] Should Return A 500 ApiError, Not The Spring Default ProblemDetail - When An Unmapped Exception Is Thrown")
    void shouldReturnA500ApiErrorNotTheSpringDefaultProblemDetailWhenAnUnmappedExceptionIsThrown() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID lookedUpUserId = UUID.randomUUID();
        when(userRepository.findSessionsInvalidatedAtById(any()))
                .thenReturn(Optional.of(sessionsInvalidatedAtView(userId, null)));
        when(userRepository.findById(lookedUpUserId))
                .thenThrow(new RuntimeException("sensitive internal detail that must never reach the client"));

        String accessToken = jwtService.generateToken(userId, TokenType.ACCESS);
        Cookie accessTokenCookie = new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, accessToken);

        mockMvc.perform(get("/users/" + lookedUpUserId).cookie(accessTokenCookie))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/users/" + lookedUpUserId))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    private UserRepository.SessionsInvalidatedAtView sessionsInvalidatedAtView(UUID id, LocalDateTime sessionsInvalidatedAt) {
        return new UserRepository.SessionsInvalidatedAtView() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public LocalDateTime getSessionsInvalidatedAt() {
                return sessionsInvalidatedAt;
            }
        };
    }
}
