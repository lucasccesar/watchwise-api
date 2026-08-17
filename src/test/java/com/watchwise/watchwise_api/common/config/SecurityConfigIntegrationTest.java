package com.watchwise.watchwise_api.common.config;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityConfigIntegrationTest {

    private static final String PROTECTED_ROUTE = "/protected-test-route";

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

    @Test
    @DisplayName("[securityFilterChain] Should Return Unauthorized Json Body - When Accessing Protected Route Without Authentication")
    void shouldReturnUnauthorizedJsonBodyWhenAccessingProtectedRouteWithoutAuthentication() throws Exception {
        mockMvc.perform(get(PROTECTED_ROUTE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value(PROTECTED_ROUTE));
    }

    @Test
    @DisplayName("[securityFilterChain] Should Return Unauthorized - When Access Token Cookie Is Malformed")
    void shouldReturnUnauthorizedWhenAccessTokenCookieIsMalformed() throws Exception {
        mockMvc.perform(get(PROTECTED_ROUTE).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, "not-a-valid-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[securityFilterChain] Should Return Unauthorized - When Cookie Holds A Refresh Token Instead Of An Access Token")
    void shouldReturnUnauthorizedWhenCookieHoldsARefreshTokenInsteadOfAnAccessToken() throws Exception {
        String refreshToken = jwtService.generateToken(UUID.randomUUID(), TokenType.REFRESH);

        mockMvc.perform(get(PROTECTED_ROUTE).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[securityFilterChain] Should Let Request Reach Spring Mvc Routing - When Access Token Cookie Is Valid")
    void shouldLetRequestReachSpringMvcRoutingWhenAccessTokenCookieIsValid() throws Exception {
        String accessToken = jwtService.generateToken(UUID.randomUUID(), TokenType.ACCESS);

        mockMvc.perform(get(PROTECTED_ROUTE).cookie(new Cookie(CookieUtil.ACCESS_TOKEN_COOKIE, accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value(PROTECTED_ROUTE));
    }

    @Test
    @DisplayName("[globalExceptionHandler] Should Return ApiError Body - When Request Method Is Not Supported For The Path")
    void shouldReturnApiErrorBodyWhenRequestMethodIsNotSupportedForThePath() throws Exception {
        mockMvc.perform(get("/auth/logout"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.path").value("/auth/logout"));
    }

    @Test
    @DisplayName("[securityFilterChain] Should Return Forbidden Json Body - When Accessing Protected Route With Post And No Csrf Token")
    void shouldReturnForbiddenJsonBodyWhenAccessingProtectedRouteWithPostAndNoCsrfToken() throws Exception {
        mockMvc.perform(post(PROTECTED_ROUTE))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.path").value(PROTECTED_ROUTE));
    }
}
