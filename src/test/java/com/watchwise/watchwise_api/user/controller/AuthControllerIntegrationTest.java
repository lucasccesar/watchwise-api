package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerIntegrationTest {

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
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("[register] Should Emit Access, Refresh And Csrf Cookies - When Registration Succeeds")
    void shouldEmitAccessRefreshAndCsrfCookiesWhenRegistrationSucceeds() throws Exception {
        mockMvc.perform(registerRequest("csrfuser", "csrfuser@email.com"))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andExpect(cookie().httpOnly(CookieUtil.CSRF_TOKEN_COOKIE, false));
    }

    @Test
    @DisplayName("[register] Should Replace A Pre-Existing Csrf Token - When A Stale Cookie Was Already Present")
    void shouldReplaceAPreExistingCsrfTokenWhenAStaleCookieWasAlreadyPresent() throws Exception {
        MvcResult anonymousResult = mockMvc.perform(get("/protected-test-route"))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andReturn();

        Cookie staleCsrfCookie = anonymousResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(staleCsrfCookie).isNotNull();

        MvcResult registerResult = mockMvc.perform(registerRequest("rotateduser", "rotateduser@email.com")
                        .cookie(staleCsrfCookie))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie freshCsrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(freshCsrfCookie).isNotNull();
        assertThat(freshCsrfCookie.getValue()).isNotEqualTo(staleCsrfCookie.getValue());
    }

    @Test
    @DisplayName("[refresh][logout] Should Rotate Refresh Token And Reject Reuse Of Old Token - When Full Auth Flow Is Executed")
    void shouldRotateRefreshTokenAndRejectReuseOfOldTokenWhenFullAuthFlowIsExecuted() throws Exception {
        mockMvc.perform(registerRequest("flowuser", "flowuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(loginRequest("flowuser"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andReturn();

        Cookie originalRefreshCookie = loginResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(originalRefreshCookie).isNotNull();

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andReturn();

        Cookie rotatedRefreshCookie = refreshResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(rotatedRefreshCookie).isNotNull();
        assertThat(rotatedRefreshCookie.getValue()).isNotEqualTo(originalRefreshCookie.getValue());

        mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isUnauthorized());

        MvcResult logoutResult = mockMvc.perform(post("/auth/logout").cookie(rotatedRefreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andReturn();
        assertCsrfCookieCleared(logoutResult);

        mockMvc.perform(post("/auth/refresh").cookie(rotatedRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("[logout] Should Clear Cookies - When No Refresh Token Cookie Is Present")
    void shouldClearCookiesWhenNoRefreshTokenCookieIsPresent() throws Exception {
        MvcResult logoutResult = mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andReturn();
        assertCsrfCookieCleared(logoutResult);
    }

    private void assertCsrfCookieCleared(MvcResult result) {
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header)
                        .startsWith(CookieUtil.CSRF_TOKEN_COOKIE + "=")
                        .contains("Max-Age=0"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder registerRequest(String username, String email) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": true
                }
                """.formatted(username, email);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String identifier) {
        String body = """
                {
                    "identifier": "%s",
                    "password": "Password123"
                }
                """.formatted(identifier);

        return post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
