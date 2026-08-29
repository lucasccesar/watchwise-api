package com.watchwise.watchwise_api.feed.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FeedControllerIntegrationTest {

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
    private DroppedEntryRepository droppedEntryRepository;

    @Autowired
    private Top5EntryRepository top5EntryRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        top5EntryRepository.deleteAll();
        droppedEntryRepository.deleteAll();
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
                    "password": "Password123"
                }
                """.formatted(username, username);

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

    private Content persistContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void persistDiaryEntry(User user, Content content, LocalDateTime createdAt) {
        persistDiaryEntry(user, content, createdAt, false);
    }

    private void persistDiaryEntry(User user, Content content, LocalDateTime createdAt, boolean ignore) {
        diaryEntryRepository.save(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .ignore(ignore)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private void persistDroppedEntry(User user, Content content, LocalDateTime createdAt) {
        droppedEntryRepository.save(DroppedEntry.builder()
                .user(user)
                .content(content)
                .type(content.getType())
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    private void persistTop5Entry(User user, Content content, LocalDateTime createdAt) {
        top5EntryRepository.save(Top5Entry.builder()
                .user(user)
                .content(content)
                .type(content.getType())
                .position(1)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }

    @Test
    @DisplayName("[getFeed] Should Return Merged Events From Followed Users Ordered By CreatedAt Desc - When Called")
    void shouldReturnMergedEventsFromFollowedUsersOrderedByCreatedAtDescWhenCalled() throws Exception {
        RegisteredUser viewer = registerUser("feedviewer");
        RegisteredUser followed = registerUser("feedfollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);

        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();

        Content movie = persistContent("550", ContentType.MOVIE);
        Content series = persistContent("1399", ContentType.SERIES);
        persistDiaryEntry(followedEntity, movie, now.minusMinutes(2));
        persistDroppedEntry(followedEntity, series, now.minusMinutes(1));
        persistTop5Entry(followedEntity, movie, now);

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].eventType").value("TOP5_UPDATE"))
                .andExpect(jsonPath("$.content[1].eventType").value("DROPPED"))
                .andExpect(jsonPath("$.content[2].eventType").value("DIARY_ENTRY"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getFeed] Should Not Include Ignored DiaryEntry Rows - When A Bulk-Logged Episode Is Marked Ignore")
    void shouldNotIncludeIgnoredDiaryEntryRowsWhenABulkLoggedEpisodeIsMarkedIgnore() throws Exception {
        RegisteredUser viewer = registerUser("feedignoreviewer");
        RegisteredUser followed = registerUser("feedignorefollowed");
        persistFollow(viewer.id(), followed.id(), FollowStatus.ACCEPTED);

        User followedEntity = userRepository.findById(followed.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();

        Content episode = contentRepository.save(Content.builder()
                .type(ContentType.EPISODE).seriesTmdbId("900").seasonNumber(1).episodeNumber(1)
                .createdAt(now).updatedAt(now).build());
        Content season = contentRepository.save(Content.builder()
                .type(ContentType.SEASON).seriesTmdbId("900").seasonNumber(1)
                .createdAt(now).updatedAt(now).build());
        persistDiaryEntry(followedEntity, episode, now.minusMinutes(1), true);
        persistDiaryEntry(followedEntity, season, now, false);

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.type").value("SEASON"));
    }

    @Test
    @DisplayName("[getFeed] Should Not Include Events From Users The Viewer Does Not Follow - When Called")
    void shouldNotIncludeEventsFromUsersTheViewerDoesNotFollowWhenCalled() throws Exception {
        RegisteredUser viewer = registerUser("feednofollow");
        RegisteredUser stranger = registerUser("feedstranger");
        User strangerEntity = userRepository.findById(stranger.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE);
        persistDiaryEntry(strangerEntity, movie, LocalDateTime.now());

        mockMvc.perform(get("/feed").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("[getFeed] Should Return BadRequest - When Size Is Zero")
    void shouldReturnBadRequestWhenSizeIsZero() throws Exception {
        RegisteredUser viewer = registerUser("feedbadsize");

        mockMvc.perform(get("/feed").param("size", "0").cookie(viewer.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be greater than 0"));
    }

    @Test
    @DisplayName("[getFeed] Should Return BadRequest - When Cursor Is Malformed")
    void shouldReturnBadRequestWhenCursorIsMalformed() throws Exception {
        RegisteredUser viewer = registerUser("feedbadcursor");

        mockMvc.perform(get("/feed").param("cursor", "not-a-valid-cursor!!").cookie(viewer.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor"));
    }

    @Test
    @DisplayName("[getFeed] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(get("/feed"))
                .andExpect(status().isUnauthorized());
    }
}
