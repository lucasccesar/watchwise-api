package com.watchwise.watchwise_api.dropped.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
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
import org.springframework.data.domain.PageRequest;
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
class DroppedEntryControllerIntegrationTest {

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
    private ContentRepository contentRepository;

    @Autowired
    private DroppedEntryRepository droppedEntryRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        droppedEntryRepository.deleteAll();
        contentRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
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

    private void persistFollow(UUID followerId, UUID followedId, FollowStatus status) {
        followerRepository.save(Follower.builder()
                .follower(userRepository.getReferenceById(followerId))
                .followed(userRepository.getReferenceById(followedId))
                .status(status)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private MockHttpServletRequestBuilder markRequest(RegisteredUser actor, ContentType type, String tmdbId) {
        return post("/users/me/dropped/" + type + "/" + tmdbId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder markRequest(RegisteredUser actor, ContentType type, String tmdbId, String comment) {
        return markRequest(actor, type, tmdbId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"comment\": \"" + comment + "\" }");
    }

    private MockHttpServletRequestBuilder unmarkRequest(RegisteredUser actor, ContentType type, String tmdbId) {
        return delete("/users/me/dropped/" + type + "/" + tmdbId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder getDroppedRequest(RegisteredUser viewer, UUID targetUserId, ContentType type) {
        return get("/users/" + targetUserId + "/dropped/" + type).cookie(viewer.accessToken());
    }

    // ---------- POST /users/me/dropped/{type}/{tmdbId} ----------

    @Test
    @DisplayName("[markAsDropped] Should Return NoContent And Persist With Comment - When Marking A New Movie")
    void shouldReturnNoContentAndPersistWithCommentWhenMarkingANewMovie() throws Exception {
        RegisteredUser user = registerUser("markdroppedok");

        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550", "Lost interest"))
                .andExpect(status().isNoContent());

        var entry = droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(user.id(), ContentType.MOVIE,
                PageRequest.of(0, 10)).getContent().getFirst();
        assertThat(entry.getComment()).isEqualTo("Lost interest");
        assertThat(entry.getContent().getId())
                .isEqualTo(contentRepository.findByTmdbIdAndType("550", ContentType.MOVIE).orElseThrow().getId());
    }

    @Test
    @DisplayName("[markAsDropped] Should Return NoContent And Persist Without Comment - When Body Is Omitted")
    void shouldReturnNoContentAndPersistWithoutCommentWhenBodyIsOmitted() throws Exception {
        RegisteredUser user = registerUser("markdroppednocomment");

        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550"))
                .andExpect(status().isNoContent());

        var entry = droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(user.id(), ContentType.MOVIE,
                PageRequest.of(0, 10)).getContent().getFirst();
        assertThat(entry.getComment()).isNull();
    }

    @Test
    @DisplayName("[markAsDropped] Should Return NoContent And Not Duplicate - When Marking The Same Movie Twice")
    void shouldReturnNoContentAndNotDuplicateWhenMarkingTheSameMovieTwice() throws Exception {
        RegisteredUser user = registerUser("markdroppedtwice");

        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550")).andExpect(status().isNoContent());
        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550")).andExpect(status().isNoContent());

        assertThat(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(user.id(), ContentType.MOVIE,
                PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("[markAsDropped] Should Overwrite The Comment - When Marking Again With A New Comment")
    void shouldOverwriteTheCommentWhenMarkingAgainWithANewComment() throws Exception {
        RegisteredUser user = registerUser("markdroppedoverwrite");
        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550", "First comment")).andExpect(status().isNoContent());

        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550", "Second comment")).andExpect(status().isNoContent());

        var entry = droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(user.id(), ContentType.MOVIE,
                PageRequest.of(0, 10)).getContent().getFirst();
        assertThat(entry.getComment()).isEqualTo("Second comment");
    }

    @Test
    @DisplayName("[markAsDropped] Should Not Change The Comment - When Marking Again Without A Comment")
    void shouldNotChangeTheCommentWhenMarkingAgainWithoutAComment() throws Exception {
        RegisteredUser user = registerUser("markdroppedkeep");
        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550", "Keep me")).andExpect(status().isNoContent());

        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550")).andExpect(status().isNoContent());

        var entry = droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(user.id(), ContentType.MOVIE,
                PageRequest.of(0, 10)).getContent().getFirst();
        assertThat(entry.getComment()).isEqualTo("Keep me");
    }

    @Test
    @DisplayName("[markAsDropped] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenMarkingWithTypeSeason() throws Exception {
        RegisteredUser user = registerUser("markdroppedseason");

        mockMvc.perform(markRequest(user, ContentType.SEASON, "550"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[markAsDropped] Should Return BadRequest - When Type Is Episode")
    void shouldReturnBadRequestWhenMarkingWithTypeEpisode() throws Exception {
        RegisteredUser user = registerUser("markdroppedepisode");

        mockMvc.perform(markRequest(user, ContentType.EPISODE, "550"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[markAsDropped] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForMark() throws Exception {
        RegisteredUser user = registerUser("markdroppednoauth");

        mockMvc.perform(post("/users/me/dropped/MOVIE/550")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[markAsDropped] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForMark() throws Exception {
        RegisteredUser user = registerUser("markdroppednocsrf");

        mockMvc.perform(post("/users/me/dropped/MOVIE/550").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[markAsDropped] Should Return TooManyRequests - When Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        RegisteredUser user = registerUser("throttlemarkdropped");

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(markRequest(user, ContentType.MOVIE, "550")).andExpect(status().isNoContent());
        }

        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[markAsDropped] Should Not Block A Different User - When Another User Is Rate Limited")
    void shouldNotBlockADifferentUserWhenAnotherUserIsRateLimited() throws Exception {
        RegisteredUser userA = registerUser("droppedactionuserA");
        RegisteredUser userB = registerUser("droppedactionuserB");

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(markRequest(userA, ContentType.MOVIE, "550")).andExpect(status().isNoContent());
        }
        mockMvc.perform(markRequest(userA, ContentType.MOVIE, "550")).andExpect(status().isTooManyRequests());

        mockMvc.perform(markRequest(userB, ContentType.MOVIE, "550")).andExpect(status().isNoContent());
    }

    // ---------- DELETE /users/me/dropped/{type}/{tmdbId} ----------

    @Test
    @DisplayName("[unmarkAsDropped] Should Return NoContent And Remove Row - When Movie Is Dropped")
    void shouldReturnNoContentAndRemoveRowWhenMovieIsDropped() throws Exception {
        RegisteredUser user = registerUser("unmarkdroppedok");
        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550")).andExpect(status().isNoContent());

        mockMvc.perform(unmarkRequest(user, ContentType.MOVIE, "550"))
                .andExpect(status().isNoContent());

        assertThat(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(user.id(), ContentType.MOVIE,
                PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Return NoContent - When Movie Was Not Dropped")
    void shouldReturnNoContentWhenMovieWasNotDropped() throws Exception {
        RegisteredUser user = registerUser("unmarkdroppednotdropped");

        mockMvc.perform(unmarkRequest(user, ContentType.MOVIE, "550"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenUnmarkingWithTypeSeason() throws Exception {
        RegisteredUser user = registerUser("unmarkdroppedseason");

        mockMvc.perform(unmarkRequest(user, ContentType.SEASON, "550"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Return BadRequest - When Type Is Episode")
    void shouldReturnBadRequestWhenUnmarkingWithTypeEpisode() throws Exception {
        RegisteredUser user = registerUser("unmarkdroppedepisode");

        mockMvc.perform(unmarkRequest(user, ContentType.EPISODE, "550"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUnmark() throws Exception {
        RegisteredUser user = registerUser("unmarkdroppednoauth");

        mockMvc.perform(delete("/users/me/dropped/MOVIE/550")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUnmark() throws Exception {
        RegisteredUser user = registerUser("unmarkdroppednocsrf");

        mockMvc.perform(delete("/users/me/dropped/MOVIE/550").cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ---------- GET /users/{userId}/dropped/{type} ----------

    @Test
    @DisplayName("[getDropped] Should Return The Entries Most Recently Created First - When Entries Exist")
    void shouldReturnTheEntriesMostRecentlyCreatedFirstWhenEntriesExist() throws Exception {
        RegisteredUser user = registerUser("getdroppedok");
        mockMvc.perform(markRequest(user, ContentType.MOVIE, "550")).andExpect(status().isNoContent());
        mockMvc.perform(markRequest(user, ContentType.MOVIE, "680")).andExpect(status().isNoContent());

        mockMvc.perform(getDroppedRequest(user, user.id(), ContentType.MOVIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].content.tmdbId").value("680"))
                .andExpect(jsonPath("$.content[1].content.tmdbId").value("550"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getDropped] Should Return Empty Content - When User Has No Entries Of That Type")
    void shouldReturnEmptyContentWhenUserHasNoEntriesOfThatType() throws Exception {
        RegisteredUser user = registerUser("getdroppedempty");

        mockMvc.perform(getDroppedRequest(user, user.id(), ContentType.MOVIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("[getDropped] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("getdroppednotfound");

        mockMvc.perform(getDroppedRequest(viewer, UUID.randomUUID(), ContentType.MOVIE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getDropped] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenTypeIsSeason() throws Exception {
        RegisteredUser user = registerUser("getdroppedseason");

        mockMvc.perform(getDroppedRequest(user, user.id(), ContentType.SEASON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getDropped] Should Return BadRequest - When Type Is Episode")
    void shouldReturnBadRequestWhenTypeIsEpisode() throws Exception {
        RegisteredUser user = registerUser("getdroppedepisode");

        mockMvc.perform(getDroppedRequest(user, user.id(), ContentType.EPISODE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getDropped] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGet() throws Exception {
        RegisteredUser user = registerUser("getdroppednoauth");

        mockMvc.perform(get("/users/" + user.id() + "/dropped/MOVIE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getDropped] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("getdroppedforbiddenviewer");
        RegisteredUser target = registerUser("getdroppedforbiddentarget", false);

        mockMvc.perform(getDroppedRequest(viewer, target.id(), ContentType.MOVIE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getDropped] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("getdroppedacceptedviewer");
        RegisteredUser target = registerUser("getdroppedacceptedtarget", false);
        persistFollow(viewer.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(getDroppedRequest(viewer, target.id(), ContentType.MOVIE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getDropped] Should Return Entries - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselves() throws Exception {
        RegisteredUser target = registerUser("getdroppedselftarget", false);

        mockMvc.perform(getDroppedRequest(target, target.id(), ContentType.MOVIE))
                .andExpect(status().isOk());
    }
}
