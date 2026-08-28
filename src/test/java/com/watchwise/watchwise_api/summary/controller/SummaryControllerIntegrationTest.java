package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SummaryControllerIntegrationTest {

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
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
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

    private MockHttpServletRequestBuilder getSummaryRequest(RegisteredUser viewer, UUID targetUserId) {
        return get("/users/" + targetUserId + "/summary").cookie(viewer.accessToken());
    }

    private Content persistContent(String tmdbId, ContentType type, Integer runtimeMinutes) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .runtimeMinutes(runtimeMinutes)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void persistEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        diaryEntryRepository.save(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    @DisplayName("[getSummary] Should Return WatchTime Computed From Movie Entries - When Type Is MOVIE")
    void shouldReturnWatchTimeComputedFromMovieEntriesWhenTypeIsMovie() throws Exception {
        RegisteredUser user = registerUser("summaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE, 139);
        persistEntry(entity, movie);

        mockMvc.perform(getSummaryRequest(user, user.id()).param("type", "MOVIE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchTime.totalMinutesWatched").value(139));
    }

    @Test
    @DisplayName("[getSummary] Should Return BadRequest - When Type Is Missing")
    void shouldReturnBadRequestWhenTypeIsMissing() throws Exception {
        RegisteredUser user = registerUser("summarynotype");

        mockMvc.perform(getSummaryRequest(user, user.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type must be one of: MOVIE, SERIES"));
    }

    @Test
    @DisplayName("[getSummary] Should Return BadRequest - When Type Is Not MOVIE Or SERIES")
    void shouldReturnBadRequestWhenTypeIsNotMovieOrSeries() throws Exception {
        RegisteredUser user = registerUser("summarybadtype");

        mockMvc.perform(getSummaryRequest(user, user.id()).param("type", "EPISODE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type must be one of: MOVIE, SERIES"));
    }

    @Test
    @DisplayName("[getSummary] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("summarynotfound");

        mockMvc.perform(getSummaryRequest(viewer, UUID.randomUUID()).param("type", "MOVIE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getSummary] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        RegisteredUser user = registerUser("summarynoauth");

        mockMvc.perform(get("/users/" + user.id() + "/summary").param("type", "MOVIE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getSummary] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("summaryforbiddenviewer");
        RegisteredUser target = registerUser("summaryforbiddentarget", false);

        mockMvc.perform(getSummaryRequest(viewer, target.id()).param("type", "MOVIE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getSummary] Should Return Ok - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnOkWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("summaryacceptedviewer");
        RegisteredUser target = registerUser("summaryacceptedtarget", false);
        persistFollow(viewer.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(getSummaryRequest(viewer, target.id()).param("type", "MOVIE"))
                .andExpect(status().isOk());
    }
}
