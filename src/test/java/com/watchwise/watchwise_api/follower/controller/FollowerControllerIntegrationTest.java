package com.watchwise.watchwise_api.follower.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;
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
class FollowerControllerIntegrationTest {

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
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        followerRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
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

    private MockHttpServletRequestBuilder postFollowRequest(RegisteredUser actor, UUID targetUserId) {
        return post("/users/" + targetUserId + "/follow")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder deleteFollowRequest(RegisteredUser actor, UUID targetUserId) {
        return delete("/users/" + targetUserId + "/follow")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder acceptFollowRequestRequest(RegisteredUser actor, UUID requesterId) {
        return post("/users/me/follow-requests/" + requesterId + "/accept")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder rejectFollowRequestRequest(RegisteredUser actor, UUID requesterId) {
        return delete("/users/me/follow-requests/" + requesterId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private void persistFollow(UUID followerId, UUID followedId, FollowStatus status) {
        followerRepository.save(Follower.builder()
                .follower(userRepository.getReferenceById(followerId))
                .followed(userRepository.getReferenceById(followedId))
                .status(status)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Test
    @DisplayName("[followUser] Should Return Accepted Status And Persist Relationship - When Target Profile Is Public")
    void shouldReturnAcceptedStatusAndPersistRelationshipWhenTargetProfileIsPublic() throws Exception {
        RegisteredUser follower = registerUser("followerpublic", true);
        RegisteredUser target = registerUser("targetpublicfollow", true);

        mockMvc.perform(postFollowRequest(follower, target.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        Optional<Follower> saved = followerRepository.findByFollowerIdAndFollowedId(follower.id(), target.id());
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[followUser] Should Return Pending Status And Persist Relationship - When Target Profile Is Private")
    void shouldReturnPendingStatusAndPersistRelationshipWhenTargetProfileIsPrivate() throws Exception {
        RegisteredUser follower = registerUser("followerprivate", true);
        RegisteredUser target = registerUser("targetprivatefollow", false);

        mockMvc.perform(postFollowRequest(follower, target.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        Optional<Follower> saved = followerRepository.findByFollowerIdAndFollowedId(follower.id(), target.id());
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(FollowStatus.PENDING);
    }

    @Test
    @DisplayName("[followUser] Should Return BadRequest - When Following Self")
    void shouldReturnBadRequestWhenFollowingSelf() throws Exception {
        RegisteredUser user = registerUser("selffollower", true);

        mockMvc.perform(postFollowRequest(user, user.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot follow yourself"));
    }

    @Test
    @DisplayName("[followUser] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenFollowTargetUserDoesNotExist() throws Exception {
        RegisteredUser follower = registerUser("followernotfound", true);

        mockMvc.perform(postFollowRequest(follower, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[followUser] Should Return Conflict - When Already Following The Target")
    void shouldReturnConflictWhenAlreadyFollowingTheTarget() throws Exception {
        RegisteredUser follower = registerUser("followerconflict", true);
        RegisteredUser target = registerUser("targetconflict", true);
        mockMvc.perform(postFollowRequest(follower, target.id())).andExpect(status().isOk());

        mockMvc.perform(postFollowRequest(follower, target.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already following or already requested to follow this user"));
    }

    @Test
    @DisplayName("[followUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForFollow() throws Exception {
        RegisteredUser target = registerUser("targetnoauthfollow", true);

        mockMvc.perform(post("/users/" + target.id() + "/follow")
                        .cookie(target.csrfToken())
                        .header("X-XSRF-TOKEN", target.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[followUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForFollow() throws Exception {
        RegisteredUser follower = registerUser("followernocsrf", true);
        RegisteredUser target = registerUser("targetnocsrf", true);

        mockMvc.perform(post("/users/" + target.id() + "/follow").cookie(follower.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[unfollowUser] Should Return NoContent And Remove Relationship - When It Exists")
    void shouldReturnNoContentAndRemoveRelationshipWhenItExists() throws Exception {
        RegisteredUser follower = registerUser("unfollowerok", true);
        RegisteredUser target = registerUser("unfollowtargetok", true);
        persistFollow(follower.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(deleteFollowRequest(follower, target.id()))
                .andExpect(status().isNoContent());

        assertThat(followerRepository.findByFollowerIdAndFollowedId(follower.id(), target.id())).isEmpty();
    }

    @Test
    @DisplayName("[unfollowUser] Should Return NotFound - When Relationship Does Not Exist")
    void shouldReturnNotFoundWhenUnfollowRelationshipDoesNotExist() throws Exception {
        RegisteredUser follower = registerUser("unfollowernotfound", true);
        RegisteredUser target = registerUser("unfollowtargetnotfound", true);

        mockMvc.perform(deleteFollowRequest(follower, target.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Follow relationship not found"));
    }

    @Test
    @DisplayName("[unfollowUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUnfollow() throws Exception {
        RegisteredUser target = registerUser("targetnoauthunfollow", true);

        mockMvc.perform(delete("/users/" + target.id() + "/follow")
                        .cookie(target.csrfToken())
                        .header("X-XSRF-TOKEN", target.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[unfollowUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUnfollow() throws Exception {
        RegisteredUser follower = registerUser("unfollowernocsrf", true);
        RegisteredUser target = registerUser("unfollowtargetnocsrf", true);
        persistFollow(follower.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(delete("/users/" + target.id() + "/follow").cookie(follower.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[followUser][unfollowUser] Should Return TooManyRequests - When Combined Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenCombinedFollowActionRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        RegisteredUser follower = registerUser("throttlefollower", true);
        RegisteredUser target = registerUser("throttletarget", true);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(postFollowRequest(follower, target.id())).andExpect(status().isOk());
            mockMvc.perform(deleteFollowRequest(follower, target.id())).andExpect(status().isNoContent());
        }

        mockMvc.perform(postFollowRequest(follower, target.id()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[followUser] Should Not Block A Different User - When Another User Is Rate Limited")
    void shouldNotBlockADifferentUserWhenAnotherUserIsRateLimitedForFollowAction() throws Exception {
        RegisteredUser followerA = registerUser("followactionuserA", true);
        RegisteredUser followerB = registerUser("followactionuserB", true);
        RegisteredUser target = registerUser("followactiontarget", true);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(postFollowRequest(followerA, target.id())).andExpect(status().isOk());
            mockMvc.perform(deleteFollowRequest(followerA, target.id())).andExpect(status().isNoContent());
        }
        mockMvc.perform(postFollowRequest(followerA, target.id()))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(postFollowRequest(followerB, target.id()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getFollowers] Should Return Accepted Followers - When Target Profile Is Public")
    void shouldReturnAcceptedFollowersWhenTargetProfileIsPublic() throws Exception {
        RegisteredUser viewer = registerUser("followersviewer", true);
        RegisteredUser target = registerUser("followerstarget", true);
        RegisteredUser follower = registerUser("followersfollower", true);
        persistFollow(follower.id(), target.id(), FollowStatus.ACCEPTED);
        persistFollow(viewer.id(), target.id(), FollowStatus.PENDING);

        mockMvc.perform(get("/users/" + target.id() + "/followers").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("followersfollower"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getFollowers] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenFollowersTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("followersnotfoundviewer", true);

        mockMvc.perform(get("/users/" + UUID.randomUUID() + "/followers").cookie(viewer.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getFollowers] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenFollowersTargetProfileIsPrivate() throws Exception {
        RegisteredUser viewer = registerUser("followersforbiddenviewer", true);
        RegisteredUser target = registerUser("followersforbiddentarget", false);

        mockMvc.perform(get("/users/" + target.id() + "/followers").cookie(viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getFollowers] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForFollowers() throws Exception {
        RegisteredUser target = registerUser("followersnoauthtarget", true);

        mockMvc.perform(get("/users/" + target.id() + "/followers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getFollowing] Should Return Accepted Following - When Target Profile Is Public")
    void shouldReturnAcceptedFollowingWhenTargetProfileIsPublic() throws Exception {
        RegisteredUser viewer = registerUser("followingviewer", true);
        RegisteredUser target = registerUser("followingtarget", true);
        RegisteredUser followedByTarget = registerUser("followingfollowed", true);
        persistFollow(target.id(), followedByTarget.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(get("/users/" + target.id() + "/following").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("followingfollowed"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getFollowing] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenFollowingTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("followingnotfoundviewer", true);

        mockMvc.perform(get("/users/" + UUID.randomUUID() + "/following").cookie(viewer.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getFollowing] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenFollowingTargetProfileIsPrivate() throws Exception {
        RegisteredUser viewer = registerUser("followingforbiddenviewer", true);
        RegisteredUser target = registerUser("followingforbiddentarget", false);

        mockMvc.perform(get("/users/" + target.id() + "/following").cookie(viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getFollowing] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForFollowing() throws Exception {
        RegisteredUser target = registerUser("followingnoauthtarget", true);

        mockMvc.perform(get("/users/" + target.id() + "/following"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Pending Requests Sent To The Authenticated User")
    void shouldReturnPendingRequestsSentToTheAuthenticatedUser() throws Exception {
        RegisteredUser target = registerUser("pendingtarget", false);
        RegisteredUser requester = registerUser("pendingrequester", true);
        persistFollow(requester.id(), target.id(), FollowStatus.PENDING);

        mockMvc.perform(get("/users/me/follow-requests").cookie(target.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("pendingrequester"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Empty Content - When No Pending Requests Exist")
    void shouldReturnEmptyContentWhenNoPendingRequestsExist() throws Exception {
        RegisteredUser target = registerUser("pendingemptytarget", true);

        mockMvc.perform(get("/users/me/follow-requests").cookie(target.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("[getPendingFollowRequests] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForPendingRequests() throws Exception {
        mockMvc.perform(get("/users/me/follow-requests"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Return NoContent And Set Status Accepted - When Pending Request Exists")
    void shouldReturnNoContentAndSetStatusAcceptedWhenPendingRequestExists() throws Exception {
        RegisteredUser target = registerUser("accepttarget", false);
        RegisteredUser requester = registerUser("acceptrequester", true);
        persistFollow(requester.id(), target.id(), FollowStatus.PENDING);

        mockMvc.perform(acceptFollowRequestRequest(target, requester.id()))
                .andExpect(status().isNoContent());

        Follower updated = followerRepository.findByFollowerIdAndFollowedId(requester.id(), target.id()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(FollowStatus.ACCEPTED);
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Return NotFound - When No Pending Request Exists")
    void shouldReturnNotFoundWhenNoPendingRequestExistsToAccept() throws Exception {
        RegisteredUser target = registerUser("acceptnotfoundtarget", true);
        RegisteredUser requester = registerUser("acceptnotfoundrequester", true);

        mockMvc.perform(acceptFollowRequestRequest(target, requester.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Follow request not found"));
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForAccept() throws Exception {
        RegisteredUser requester = registerUser("acceptnoauthrequester", true);

        mockMvc.perform(post("/users/me/follow-requests/" + requester.id() + "/accept")
                        .cookie(requester.csrfToken())
                        .header("X-XSRF-TOKEN", requester.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[acceptFollowRequest] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForAccept() throws Exception {
        RegisteredUser target = registerUser("acceptnocsrftarget", false);
        RegisteredUser requester = registerUser("acceptnocsrfrequester", true);
        persistFollow(requester.id(), target.id(), FollowStatus.PENDING);

        mockMvc.perform(post("/users/me/follow-requests/" + requester.id() + "/accept").cookie(target.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Return NoContent And Remove Request - When Pending Request Exists")
    void shouldReturnNoContentAndRemoveRequestWhenPendingRequestExists() throws Exception {
        RegisteredUser target = registerUser("rejecttarget", false);
        RegisteredUser requester = registerUser("rejectrequester", true);
        persistFollow(requester.id(), target.id(), FollowStatus.PENDING);

        mockMvc.perform(rejectFollowRequestRequest(target, requester.id()))
                .andExpect(status().isNoContent());

        assertThat(followerRepository.findByFollowerIdAndFollowedId(requester.id(), target.id())).isEmpty();
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Return NotFound - When No Pending Request Exists")
    void shouldReturnNotFoundWhenNoPendingRequestExistsToReject() throws Exception {
        RegisteredUser target = registerUser("rejectnotfoundtarget", true);
        RegisteredUser requester = registerUser("rejectnotfoundrequester", true);

        mockMvc.perform(rejectFollowRequestRequest(target, requester.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Follow request not found"));
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForReject() throws Exception {
        RegisteredUser requester = registerUser("rejectnoauthrequester", true);

        mockMvc.perform(delete("/users/me/follow-requests/" + requester.id())
                        .cookie(requester.csrfToken())
                        .header("X-XSRF-TOKEN", requester.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[rejectFollowRequest] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForReject() throws Exception {
        RegisteredUser target = registerUser("rejectnocsrftarget", false);
        RegisteredUser requester = registerUser("rejectnocsrfrequester", true);
        persistFollow(requester.id(), target.id(), FollowStatus.PENDING);

        mockMvc.perform(delete("/users/me/follow-requests/" + requester.id()).cookie(target.accessToken()))
                .andExpect(status().isForbidden());
    }
}