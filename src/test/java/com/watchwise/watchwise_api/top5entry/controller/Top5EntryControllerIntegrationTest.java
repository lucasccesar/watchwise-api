package com.watchwise.watchwise_api.top5entry.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class Top5EntryControllerIntegrationTest {

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
    private Top5EntryRepository top5EntryRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        top5EntryRepository.deleteAll();
        contentRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);

        lenient().when(tmdbClient.getMovieFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        lenient().when(tmdbClient.getTvFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
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

    private MockHttpServletRequestBuilder insertRequest(RegisteredUser actor, ContentType type, String body) {
        return post("/users/me/top5/" + type)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder removeRequest(RegisteredUser actor, ContentType type, UUID top5EntryId) {
        return delete("/users/me/top5/" + type + "/" + top5EntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder updateRequest(RegisteredUser actor, ContentType type, UUID top5EntryId, String body) {
        return patch("/users/me/top5/" + type + "/" + top5EntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String posterPatchBody(String customPosterUrl) {
        return """
                {
                    "customPosterUrl": "%s"
                }
                """.formatted(customPosterUrl);
    }

    private MockHttpServletRequestBuilder getTop5Request(RegisteredUser viewer, UUID targetUserId, ContentType type) {
        return get("/users/" + targetUserId + "/top5/" + type).cookie(viewer.accessToken());
    }

    private String contentBody(String tmdbId, Integer position) {
        String positionField = position == null ? "" : (", \"position\": " + position);
        return """
                {
                    "tmdbId": "%s"%s
                }
                """.formatted(tmdbId, positionField);
    }

    private String contentBody(String tmdbId, Integer position, String customPosterUrl) {
        String positionField = position == null ? "" : (", \"position\": " + position);
        String posterField = customPosterUrl == null ? "" : (", \"customPosterUrl\": \"" + customPosterUrl + "\"");
        return """
                {
                    "tmdbId": "%s"%s%s
                }
                """.formatted(tmdbId, positionField, posterField);
    }

    private Top5Entry persistEntry(User user, Content content, ContentType type, int position) {
        LocalDateTime now = LocalDateTime.now();
        return top5EntryRepository.save(Top5Entry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
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

    // ---------- GET /users/{userId}/top5/{type} ----------

    @Test
    @DisplayName("[getTop5] Should Return The Entries Ordered By Position - When Entries Exist")
    void shouldReturnTheEntriesOrderedByPositionWhenEntriesExist() throws Exception {
        RegisteredUser user = registerUser("gettop5ok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content fightClub = persistContent("550", ContentType.MOVIE);
        Content pulpFiction = persistContent("680", ContentType.MOVIE);
        persistEntry(entity, pulpFiction, ContentType.MOVIE, 2);
        persistEntry(entity, fightClub, ContentType.MOVIE, 1);

        mockMvc.perform(getTop5Request(user, user.id(), ContentType.MOVIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content.tmdbId").value("550"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].content.tmdbId").value("680"))
                .andExpect(jsonPath("$[1].position").value(2));
    }

    @Test
    @DisplayName("[getTop5] Should Return Empty List - When User Has No Entries Of That Type")
    void shouldReturnEmptyListWhenUserHasNoEntriesOfThatType() throws Exception {
        RegisteredUser user = registerUser("gettop5empty");

        mockMvc.perform(getTop5Request(user, user.id(), ContentType.MOVIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("[getTop5] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("gettop5notfound");

        mockMvc.perform(getTop5Request(viewer, UUID.randomUUID(), ContentType.MOVIE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getTop5] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenTypeIsSeason() throws Exception {
        RegisteredUser user = registerUser("gettop5season");

        mockMvc.perform(getTop5Request(user, user.id(), ContentType.SEASON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getTop5] Should Return BadRequest - When Type Is Episode")
    void shouldReturnBadRequestWhenTypeIsEpisode() throws Exception {
        RegisteredUser user = registerUser("gettop5episode");

        mockMvc.perform(getTop5Request(user, user.id(), ContentType.EPISODE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getTop5] Should Return BadRequest With Consistent Body - When Type Is Not A Valid Enum Value")
    void shouldReturnBadRequestWithConsistentBodyWhenTypeIsNotAValidEnumValue() throws Exception {
        RegisteredUser user = registerUser("gettop5invalidenum");

        mockMvc.perform(get("/users/" + user.id() + "/top5/NOT_A_TYPE").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Accepted values: MOVIE, SERIES")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SEASON"))))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("[getTop5] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGet() throws Exception {
        RegisteredUser user = registerUser("gettop5noauth");

        mockMvc.perform(get("/users/" + user.id() + "/top5/MOVIE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getTop5] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("gettop5forbiddenviewer");
        RegisteredUser target = registerUser("gettop5forbiddentarget", false);

        mockMvc.perform(getTop5Request(viewer, target.id(), ContentType.MOVIE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getTop5] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("gettop5acceptedviewer");
        RegisteredUser target = registerUser("gettop5acceptedtarget", false);
        persistFollow(viewer.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(getTop5Request(viewer, target.id(), ContentType.MOVIE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getTop5] Should Return Entries - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselves() throws Exception {
        RegisteredUser target = registerUser("gettop5selftarget", false);

        mockMvc.perform(getTop5Request(target, target.id(), ContentType.MOVIE))
                .andExpect(status().isOk());
    }

    // ---------- POST /users/me/top5/{type} ----------

    @Test
    @DisplayName("[insertEntry] Should Return Created And Persist At Position One - When List Is Empty")
    void shouldReturnCreatedAndPersistAtPositionOneWhenListIsEmpty() throws Exception {
        RegisteredUser user = registerUser("inserttop5empty");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.type").value("MOVIE"))
                .andExpect(jsonPath("$.content.tmdbId").value("550"));

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE))
                .hasSize(1);
    }

    @Test
    @DisplayName("[insertEntry] Should Persist CustomPosterUrl - When Provided")
    void shouldPersistCustomPosterUrlWhenProvidedOnInsert() throws Exception {
        RegisteredUser user = registerUser("inserttop5poster");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550", null, "https://example.com/poster.png")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/poster.png"));

        User entity = userRepository.findById(user.id()).orElseThrow();
        var entries = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries.get(0).getCustomPosterUrl()).isEqualTo("https://example.com/poster.png");
    }

    @Test
    @DisplayName("[insertEntry] Should Shift Existing Entries - When Inserting At An Occupied Position")
    void shouldShiftExistingEntriesWhenInsertingAtAnOccupiedPosition() throws Exception {
        RegisteredUser user = registerUser("inserttop5shift");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content fightClub = persistContent("550", ContentType.MOVIE);
        persistEntry(entity, fightClub, ContentType.MOVIE, 1);

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("680", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.content.tmdbId").value("680"));

        Content pulpFiction = contentRepository.findByTmdbIdAndType("680", ContentType.MOVIE).orElseThrow();
        var entries = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getContent().getId()).isEqualTo(pulpFiction.getId());
        assertThat(entries.get(0).getPosition()).isEqualTo(1);
        assertThat(entries.get(1).getContent().getId()).isEqualTo(fightClub.getId());
        assertThat(entries.get(1).getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("[insertEntry] Should Evict Position Five - When Inserting Into A Full List")
    void shouldEvictPositionFiveWhenInsertingIntoAFullList() throws Exception {
        RegisteredUser user = registerUser("inserttop5full");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UUID evictedContentId = null;
        for (int i = 1; i <= 5; i++) {
            Content content = persistContent("movie" + i, ContentType.MOVIE);
            persistEntry(entity, content, ContentType.MOVIE, i);
            if (i == 5) {
                evictedContentId = content.getId();
            }
        }

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("newmovie", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1));

        var entries = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).hasSize(5);
        assertThat(entries).extracting(e -> e.getContent().getId()).doesNotContain(evictedContentId);
    }

    @Test
    @DisplayName("[insertEntry] Should Return Conflict - When Content Is Already In The Top 5")
    void shouldReturnConflictWhenContentIsAlreadyInTheTop5() throws Exception {
        RegisteredUser user = registerUser("inserttop5conflict");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This content is already in your top 5"));
    }

    @Test
    @DisplayName("[insertEntry] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenInsertingWithTypeSeason() throws Exception {
        RegisteredUser user = registerUser("inserttop5season");

        mockMvc.perform(insertRequest(user, ContentType.SEASON, contentBody("550", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[insertEntry] Should Return BadRequest - When Position Is Omitted And Top 5 Is Already Full")
    void shouldReturnBadRequestWhenPositionIsOmittedAndTop5IsAlreadyFull() throws Exception {
        RegisteredUser user = registerUser("inserttop5nopositionfull");
        User entity = userRepository.findById(user.id()).orElseThrow();
        for (int i = 1; i <= 5; i++) {
            persistEntry(entity, persistContent("movie" + i, ContentType.MOVIE), ContentType.MOVIE, i);
        }

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("newmovie", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[insertEntry] Should Return BadRequest And Not Persist - When TmdbId Field Is Missing")
    void shouldReturnBadRequestAndNotPersistWhenTmdbIdFieldIsMissing() throws Exception {
        RegisteredUser user = registerUser("inserttop5missingcontent");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, "{ \"position\": 1 }"))
                .andExpect(status().isBadRequest());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE)).isEmpty();
    }

    @Test
    @DisplayName("[insertEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForInsert() throws Exception {
        RegisteredUser user = registerUser("inserttop5noauth");

        mockMvc.perform(post("/users/me/top5/MOVIE")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentBody("550", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[insertEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForInsert() throws Exception {
        RegisteredUser user = registerUser("inserttop5nocsrf");

        mockMvc.perform(post("/users/me/top5/MOVIE")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentBody("550", null)))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /users/me/top5/{type}/{top5EntryId} ----------

    @Test
    @DisplayName("[removeEntry] Should Return NoContent And Close The Gap - When Entry Exists")
    void shouldReturnNoContentAndCloseTheGapWhenEntryExists() throws Exception {
        RegisteredUser user = registerUser("removetop5ok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Top5Entry toRemove = persistEntry(entity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);
        Content pulpFiction = persistContent("680", ContentType.MOVIE);
        persistEntry(entity, pulpFiction, ContentType.MOVIE, 2);

        mockMvc.perform(removeRequest(user, ContentType.MOVIE, toRemove.getId()))
                .andExpect(status().isNoContent());

        var entries = top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent().getId()).isEqualTo(pulpFiction.getId());
        assertThat(entries.get(0).getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("[removeEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("removetop5notfound");

        mockMvc.perform(removeRequest(user, ContentType.MOVIE, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Top 5 entry not found"));
    }

    @Test
    @DisplayName("[removeEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("removetop5owner");
        RegisteredUser intruder = registerUser("removetop5intruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        Top5Entry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(removeRequest(intruder, ContentType.MOVIE, entry.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Top 5 entry not found"));

        assertThat(top5EntryRepository.findById(entry.getId())).isPresent();
    }

    @Test
    @DisplayName("[removeEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForRemove() throws Exception {
        RegisteredUser user = registerUser("removetop5noauth");

        mockMvc.perform(delete("/users/me/top5/MOVIE/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[removeEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForRemove() throws Exception {
        RegisteredUser user = registerUser("removetop5nocsrf");

        mockMvc.perform(delete("/users/me/top5/MOVIE/" + UUID.randomUUID())
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ---------- PATCH /users/me/top5/{type}/{top5EntryId} ----------

    @Test
    @DisplayName("[updateEntry] Should Return Ok And Persist The New Poster - When Entry Exists")
    void shouldReturnOkAndPersistTheNewPosterWhenEntryExists() throws Exception {
        RegisteredUser user = registerUser("updatetop5ok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Top5Entry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(updateRequest(user, ContentType.MOVIE, entry.getId(), posterPatchBody("https://example.com/new.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/new.png"));

        assertThat(top5EntryRepository.findById(entry.getId()).orElseThrow().getCustomPosterUrl())
                .isEqualTo("https://example.com/new.png");
    }

    @Test
    @DisplayName("[updateEntry] Should Return BadRequest - When CustomPosterUrl Is Not A Valid Url")
    void shouldReturnBadRequestWhenCustomPosterUrlIsNotAValidUrl() throws Exception {
        RegisteredUser user = registerUser("updatetop5invalidurl");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Top5Entry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(updateRequest(user, ContentType.MOVIE, entry.getId(), posterPatchBody("not-a-url")))
                .andExpect(status().isBadRequest());

        assertThat(top5EntryRepository.findById(entry.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }

    @Test
    @DisplayName("[updateEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExistOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatetop5notfound");

        mockMvc.perform(updateRequest(user, ContentType.MOVIE, UUID.randomUUID(), posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Top 5 entry not found"));
    }

    @Test
    @DisplayName("[updateEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUserOnUpdate() throws Exception {
        RegisteredUser owner = registerUser("updatetop5owner");
        RegisteredUser intruder = registerUser("updatetop5intruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        Top5Entry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(updateRequest(intruder, ContentType.MOVIE, entry.getId(), posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Top 5 entry not found"));

        assertThat(top5EntryRepository.findById(entry.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }

    @Test
    @DisplayName("[updateEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatetop5noauth");

        mockMvc.perform(patch("/users/me/top5/MOVIE/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatetop5nocsrf");

        mockMvc.perform(patch("/users/me/top5/MOVIE/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posterPatchBody("https://example.com/x.png")))
                .andExpect(status().isForbidden());
    }
}
