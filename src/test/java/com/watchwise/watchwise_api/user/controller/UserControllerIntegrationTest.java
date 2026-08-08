package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.user.entity.User;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserControllerIntegrationTest {

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
    @DisplayName("[updateCurrentUser] Should Update Authenticated User - When Request Is Valid")
    void shouldUpdateAuthenticatedUserWhenRequestIsValid() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("johndoe", "johndoe@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("johndoe"))
                .andExpect(jsonPath("$.description").value("Updated bio"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Only The Authenticated User - When Another User Exists")
    void shouldUpdateOnlyTheAuthenticatedUserWhenAnotherUserExists() throws Exception {
        mockMvc.perform(registerRequest("userone", "userone@email.com")).andExpect(status().isCreated());

        MvcResult registerResultTwo = mockMvc.perform(registerRequest("usertwo", "usertwo@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookieTwo = registerResultTwo.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookieTwo = registerResultTwo.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookieTwo, csrfCookieTwo, "{ \"description\": \"User two bio\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("usertwo"))
                .andExpect(jsonPath("$.description").value("User two bio"));

        User userOne = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("userone", "userone")
                .orElseThrow();
        assertThat(userOne.getDescription()).isNull();
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocookieuser", "nocookieuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patch("/users/me")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissing() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocsrfuser", "nocsrfuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(patch("/users/me")
                        .cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder patchMeRequest(Cookie accessTokenCookie, Cookie csrfCookie, String body) {
        return patch("/users/me")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, String email) {
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
}
