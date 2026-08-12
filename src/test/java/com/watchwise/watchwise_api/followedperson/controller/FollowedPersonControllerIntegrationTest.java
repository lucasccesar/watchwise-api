package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.followedperson.repository.FollowedPersonRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FollowedPersonControllerIntegrationTest {

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
    private FollowedPersonRepository followedPersonRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        followedPersonRepository.deleteAll();
        userRepository.deleteAll();
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessTokenCookie, csrfCookie);
    }

    private MockHttpServletRequestBuilder registerRequest(String username) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123",
                    "isProfilePublic": true
                }
                """.formatted(username, username);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder followPersonRequest(RegisteredUser actor, String personTmdbId) {
        return post("/users/me/follow-people/" + personTmdbId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder unfollowPersonRequest(RegisteredUser actor, String personTmdbId) {
        return delete("/users/me/follow-people/" + personTmdbId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    @Test
    @DisplayName("[followPerson] Should Return NoContent And Persist Row - When Following A New Person")
    void shouldReturnNoContentAndPersistRowWhenFollowingANewPerson() throws Exception {
        RegisteredUser user = registerUser("followpersonok");

        mockMvc.perform(followPersonRequest(user, "603"))
                .andExpect(status().isNoContent());

        assertThat(followedPersonRepository.findByUserIdAndPersonTmdbId(user.id(), "603")).isPresent();
    }

    @Test
    @DisplayName("[followPerson] Should Return NoContent And Not Duplicate - When Following The Same Person Twice")
    void shouldReturnNoContentAndNotDuplicateWhenFollowingTheSamePersonTwice() throws Exception {
        RegisteredUser user = registerUser("followpersontwice");

        mockMvc.perform(followPersonRequest(user, "603")).andExpect(status().isNoContent());
        mockMvc.perform(followPersonRequest(user, "603")).andExpect(status().isNoContent());

        long count = followedPersonRepository.findAll().stream()
                .filter(fp -> fp.getUser().getId().equals(user.id()) && fp.getPersonTmdbId().equals("603"))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("[followPerson] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForFollow() throws Exception {
        RegisteredUser user = registerUser("followpersonnoauth");

        mockMvc.perform(post("/users/me/follow-people/603")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[followPerson] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForFollow() throws Exception {
        RegisteredUser user = registerUser("followpersonnocsrf");

        mockMvc.perform(post("/users/me/follow-people/603").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[unfollowPerson] Should Return NoContent And Remove Row - When Person Is Followed")
    void shouldReturnNoContentAndRemoveRowWhenPersonIsFollowed() throws Exception {
        RegisteredUser user = registerUser("unfollowpersonok");
        mockMvc.perform(followPersonRequest(user, "603")).andExpect(status().isNoContent());

        mockMvc.perform(unfollowPersonRequest(user, "603"))
                .andExpect(status().isNoContent());

        assertThat(followedPersonRepository.findByUserIdAndPersonTmdbId(user.id(), "603")).isEmpty();
    }

    @Test
    @DisplayName("[unfollowPerson] Should Return NoContent - When Person Was Not Followed")
    void shouldReturnNoContentWhenPersonWasNotFollowed() throws Exception {
        RegisteredUser user = registerUser("unfollowpersonnotfollowed");

        mockMvc.perform(unfollowPersonRequest(user, "603"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[unfollowPerson] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUnfollow() throws Exception {
        RegisteredUser user = registerUser("unfollowpersonnoauth");

        mockMvc.perform(delete("/users/me/follow-people/603")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[unfollowPerson] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUnfollow() throws Exception {
        RegisteredUser user = registerUser("unfollowpersonnocsrf");

        mockMvc.perform(delete("/users/me/follow-people/603").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }
}
