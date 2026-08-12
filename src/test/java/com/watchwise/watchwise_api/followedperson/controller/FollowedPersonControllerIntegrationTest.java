package com.watchwise.watchwise_api.followedperson.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        followedPersonRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        return registerUser(username, true);
    }

    private RegisteredUser registerUser(String username, boolean isProfilePublic) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username, isProfilePublic))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessTokenCookie, csrfCookie);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, boolean isProfilePublic) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, username, isProfilePublic);

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

    @Test
    @DisplayName("[followPerson][unfollowPerson] Should Return TooManyRequests - When Combined Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenCombinedFollowPeopleActionRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        RegisteredUser user = registerUser("throttlefollowperson");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(followPersonRequest(user, "603")).andExpect(status().isNoContent());
            mockMvc.perform(unfollowPersonRequest(user, "603")).andExpect(status().isNoContent());
        }

        mockMvc.perform(followPersonRequest(user, "603"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[followPerson] Should Not Block A Different User - When Another User Is Rate Limited")
    void shouldNotBlockADifferentUserWhenAnotherUserIsRateLimitedForFollowPeopleAction() throws Exception {
        RegisteredUser userA = registerUser("followpeopleactionuserA");
        RegisteredUser userB = registerUser("followpeopleactionuserB");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(followPersonRequest(userA, "603")).andExpect(status().isNoContent());
            mockMvc.perform(unfollowPersonRequest(userA, "603")).andExpect(status().isNoContent());
        }
        mockMvc.perform(followPersonRequest(userA, "603"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(followPersonRequest(userB, "603"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Followed PersonTmdbIds - When Target Profile Is Public")
    void shouldReturnFollowedPersonTmdbIdsWhenTargetProfileIsPublic() throws Exception {
        RegisteredUser viewer = registerUser("followedpeopleviewer");
        RegisteredUser target = registerUser("followedpeopletarget");
        mockMvc.perform(followPersonRequest(target, "603")).andExpect(status().isNoContent());

        mockMvc.perform(get("/users/" + target.id() + "/follow-people").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("603"));
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenFollowedPeopleTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("followedpeoplenotfoundviewer");

        mockMvc.perform(get("/users/" + UUID.randomUUID() + "/follow-people").cookie(viewer.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenFollowedPeopleTargetProfileIsPrivate() throws Exception {
        RegisteredUser viewer = registerUser("followedpeopleforbiddenviewer");
        RegisteredUser target = registerUser("followedpeopleforbiddentarget", false);

        mockMvc.perform(get("/users/" + target.id() + "/follow-people").cookie(viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getFollowedPeople] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForFollowedPeople() throws Exception {
        RegisteredUser target = registerUser("followedpeoplenoauthtarget");

        mockMvc.perform(get("/users/" + target.id() + "/follow-people"))
                .andExpect(status().isUnauthorized());
    }
}
