package com.watchwise.watchwise_api.watchlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WatchlistEntryControllerIntegrationTest {

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
    private WatchlistEntryRepository watchlistEntryRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        watchlistEntryRepository.deleteAll();
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

    private MockHttpServletRequestBuilder insertRequest(RegisteredUser actor, ContentType type, String body) {
        return post("/users/me/watchlist/" + type)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder moveRequest(RegisteredUser actor, ContentType type, UUID watchlistEntryId, String body) {
        return patch("/users/me/watchlist/" + type + "/" + watchlistEntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder removeRequest(RegisteredUser actor, ContentType type, UUID watchlistEntryId) {
        return delete("/users/me/watchlist/" + type + "/" + watchlistEntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder getWatchlistRequest(RegisteredUser viewer, UUID targetUserId, ContentType type) {
        return get("/users/" + targetUserId + "/watchlist/" + type).cookie(viewer.accessToken());
    }

    private String contentBody(String tmdbId) {
        return """
                {
                    "tmdbId": "%s"
                }
                """.formatted(tmdbId);
    }

    private String positionBody(Integer position) {
        return """
                {
                    "position": %s
                }
                """.formatted(position);
    }

    private WatchlistEntry persistEntry(User user, Content content, ContentType type, int position) {
        LocalDateTime now = LocalDateTime.now();
        return watchlistEntryRepository.save(WatchlistEntry.builder()
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

    // ---------- GET /users/{userId}/watchlist/{type} ----------

    @Test
    @DisplayName("[getWatchlist] Should Return The Entries Ordered By Position - When Entries Exist")
    void shouldReturnTheEntriesOrderedByPositionWhenEntriesExist() throws Exception {
        RegisteredUser user = registerUser("getwatchlistok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content fightClub = persistContent("550", ContentType.MOVIE);
        Content pulpFiction = persistContent("680", ContentType.MOVIE);
        persistEntry(entity, pulpFiction, ContentType.MOVIE, 2);
        persistEntry(entity, fightClub, ContentType.MOVIE, 1);

        mockMvc.perform(getWatchlistRequest(user, user.id(), ContentType.MOVIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].content.tmdbId").value("550"))
                .andExpect(jsonPath("$.content[0].position").value(1))
                .andExpect(jsonPath("$.content[1].content.tmdbId").value("680"))
                .andExpect(jsonPath("$.content[1].position").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Empty Page - When User Has No Entries Of That Type")
    void shouldReturnEmptyPageWhenUserHasNoEntriesOfThatType() throws Exception {
        RegisteredUser user = registerUser("getwatchlistempty");

        mockMvc.perform(getWatchlistRequest(user, user.id(), ContentType.MOVIE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("[getWatchlist] Should Paginate Results - When Page And Size Are Provided")
    void shouldPaginateResultsWhenPageAndSizeAreProvided() throws Exception {
        RegisteredUser user = registerUser("getwatchlistpage");
        User entity = userRepository.findById(user.id()).orElseThrow();
        persistEntry(entity, persistContent("1", ContentType.MOVIE), ContentType.MOVIE, 1);
        persistEntry(entity, persistContent("2", ContentType.MOVIE), ContentType.MOVIE, 2);
        persistEntry(entity, persistContent("3", ContentType.MOVIE), ContentType.MOVIE, 3);

        mockMvc.perform(getWatchlistRequest(user, user.id(), ContentType.MOVIE).param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.tmdbId").value("2"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("[getWatchlist] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("getwatchlistnotfound");

        mockMvc.perform(getWatchlistRequest(viewer, UUID.randomUUID(), ContentType.MOVIE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getWatchlist] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenTypeIsSeason() throws Exception {
        RegisteredUser user = registerUser("getwatchlistseason");

        mockMvc.perform(getWatchlistRequest(user, user.id(), ContentType.SEASON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getWatchlist] Should Return BadRequest - When Type Is Episode")
    void shouldReturnBadRequestWhenTypeIsEpisode() throws Exception {
        RegisteredUser user = registerUser("getwatchlistepisode");

        mockMvc.perform(getWatchlistRequest(user, user.id(), ContentType.EPISODE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getWatchlist] Should Return BadRequest - When Page Number Is Negative")
    void shouldReturnBadRequestWhenPageNumberIsNegative() throws Exception {
        RegisteredUser user = registerUser("getwatchlistnegpage");

        mockMvc.perform(getWatchlistRequest(user, user.id(), ContentType.MOVIE).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getWatchlist] Should Return BadRequest With Consistent Body - When Type Is Not A Valid Enum Value")
    void shouldReturnBadRequestWithConsistentBodyWhenTypeIsNotAValidEnumValue() throws Exception {
        RegisteredUser user = registerUser("getwatchlistinvalidenum");

        mockMvc.perform(get("/users/" + user.id() + "/watchlist/NOT_A_TYPE").cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Accepted values: MOVIE, SERIES, SEASON, EPISODE")))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGet() throws Exception {
        RegisteredUser user = registerUser("getwatchlistnoauth");

        mockMvc.perform(get("/users/" + user.id() + "/watchlist/MOVIE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("getwatchlistforbiddenviewer");
        RegisteredUser target = registerUser("getwatchlistforbiddentarget", false);

        mockMvc.perform(getWatchlistRequest(viewer, target.id(), ContentType.MOVIE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("getwatchlistacceptedviewer");
        RegisteredUser target = registerUser("getwatchlistacceptedtarget", false);
        persistFollow(viewer.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(getWatchlistRequest(viewer, target.id(), ContentType.MOVIE))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Entries - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselves() throws Exception {
        RegisteredUser target = registerUser("getwatchlistselftarget", false);

        mockMvc.perform(getWatchlistRequest(target, target.id(), ContentType.MOVIE))
                .andExpect(status().isOk());
    }

    // ---------- POST /users/me/watchlist/{type} ----------

    @Test
    @DisplayName("[insertEntry] Should Return Created And Persist At Position One - When Watchlist Is Empty")
    void shouldReturnCreatedAndPersistAtPositionOneWhenWatchlistIsEmpty() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistempty");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.type").value("MOVIE"))
                .andExpect(jsonPath("$.content.tmdbId").value("550"));

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE))
                .hasSize(1);
    }

    @Test
    @DisplayName("[insertEntry] Should Append At The Last Position - When Watchlist Already Has Entries")
    void shouldAppendAtTheLastPositionWhenWatchlistAlreadyHasEntries() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistappend");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content fightClub = persistContent("550", ContentType.MOVIE);
        persistEntry(entity, fightClub, ContentType.MOVIE, 1);

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("680")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.content.tmdbId").value("680"));

        var entries = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getContent().getId()).isEqualTo(fightClub.getId());
        assertThat(entries.get(0).getPosition()).isEqualTo(1);
        assertThat(entries.get(1).getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("[insertEntry] Should Return Conflict - When Content Is Already In The Watchlist")
    void shouldReturnConflictWhenContentIsAlreadyInTheWatchlist() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistconflict");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550")))
                .andExpect(status().isCreated());

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, contentBody("550")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This content is already in your watchlist"));
    }

    @Test
    @DisplayName("[insertEntry] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenInsertingWithTypeSeason() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistseason");

        mockMvc.perform(insertRequest(user, ContentType.SEASON, contentBody("550")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[insertEntry] Should Return BadRequest And Not Persist - When TmdbId Field Is Missing")
    void shouldReturnBadRequestAndNotPersistWhenTmdbIdFieldIsMissing() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistmissing");

        mockMvc.perform(insertRequest(user, ContentType.MOVIE, "{}"))
                .andExpect(status().isBadRequest());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE)).isEmpty();
    }

    @Test
    @DisplayName("[insertEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForInsert() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistnoauth");

        mockMvc.perform(post("/users/me/watchlist/MOVIE")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentBody("550")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[insertEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForInsert() throws Exception {
        RegisteredUser user = registerUser("insertwatchlistnocsrf");

        mockMvc.perform(post("/users/me/watchlist/MOVIE")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentBody("550")))
                .andExpect(status().isForbidden());
    }

    // ---------- PATCH /users/me/watchlist/{type}/{watchlistEntryId} ----------

    @Test
    @DisplayName("[moveEntry] Should Move Forward And Shift Entries Between New And Old Position - When Position Is Earlier")
    void shouldMoveForwardAndShiftEntriesBetweenNewAndOldPositionWhenPositionIsEarlier() throws Exception {
        RegisteredUser user = registerUser("movewatchlistforward");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content1 = persistContent("1", ContentType.MOVIE);
        Content content2 = persistContent("2", ContentType.MOVIE);
        Content content3 = persistContent("3", ContentType.MOVIE);
        WatchlistEntry a = persistEntry(entity, content1, ContentType.MOVIE, 1);
        WatchlistEntry b = persistEntry(entity, content2, ContentType.MOVIE, 2);
        WatchlistEntry c = persistEntry(entity, content3, ContentType.MOVIE, 3);

        mockMvc.perform(moveRequest(user, ContentType.MOVIE, c.getId(), positionBody(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.content.tmdbId").value("3"));

        var entries = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).extracting(e -> e.getContent().getId()).containsExactly(content3.getId(), content1.getId(), content2.getId());
        assertThat(entries).extracting(WatchlistEntry::getPosition).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("[moveEntry] Should Move Backward And Shift Entries Between Old And New Position - When Position Is Later")
    void shouldMoveBackwardAndShiftEntriesBetweenOldAndNewPositionWhenPositionIsLater() throws Exception {
        RegisteredUser user = registerUser("movewatchlistbackward");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content1 = persistContent("1", ContentType.MOVIE);
        Content content2 = persistContent("2", ContentType.MOVIE);
        Content content3 = persistContent("3", ContentType.MOVIE);
        WatchlistEntry a = persistEntry(entity, content1, ContentType.MOVIE, 1);
        WatchlistEntry b = persistEntry(entity, content2, ContentType.MOVIE, 2);
        WatchlistEntry c = persistEntry(entity, content3, ContentType.MOVIE, 3);

        mockMvc.perform(moveRequest(user, ContentType.MOVIE, a.getId(), positionBody(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.content.tmdbId").value("1"));

        var entries = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).extracting(e -> e.getContent().getId()).containsExactly(content2.getId(), content3.getId(), content1.getId());
        assertThat(entries).extracting(WatchlistEntry::getPosition).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("[moveEntry] Should Return Ok Without Changing Order - When Position Equals The Current Position")
    void shouldReturnOkWithoutChangingOrderWhenPositionEqualsTheCurrentPosition() throws Exception {
        RegisteredUser user = registerUser("movewatchlistnoop");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content content1 = persistContent("1", ContentType.MOVIE);
        Content content2 = persistContent("2", ContentType.MOVIE);
        WatchlistEntry a = persistEntry(entity, content1, ContentType.MOVIE, 1);
        WatchlistEntry b = persistEntry(entity, content2, ContentType.MOVIE, 2);

        mockMvc.perform(moveRequest(user, ContentType.MOVIE, a.getId(), positionBody(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));

        var entries = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).extracting(e -> e.getContent().getId()).containsExactly(content1.getId(), content2.getId());
    }

    @Test
    @DisplayName("[moveEntry] Should Return BadRequest - When Position Is Greater Than The Current Entry Count")
    void shouldReturnBadRequestWhenPositionIsGreaterThanTheCurrentEntryCount() throws Exception {
        RegisteredUser user = registerUser("movewatchlisttoofar");
        User entity = userRepository.findById(user.id()).orElseThrow();
        WatchlistEntry a = persistEntry(entity, persistContent("1", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(moveRequest(user, ContentType.MOVIE, a.getId(), positionBody(2)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[moveEntry] Should Return BadRequest - When Position Is Below One")
    void shouldReturnBadRequestWhenPositionIsBelowOne() throws Exception {
        RegisteredUser user = registerUser("movewatchlistbelowone");
        User entity = userRepository.findById(user.id()).orElseThrow();
        WatchlistEntry a = persistEntry(entity, persistContent("1", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(moveRequest(user, ContentType.MOVIE, a.getId(), positionBody(0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[moveEntry] Should Return BadRequest - When Type Is Season")
    void shouldReturnBadRequestWhenMovingWithTypeSeason() throws Exception {
        RegisteredUser user = registerUser("movewatchlistseason");

        mockMvc.perform(moveRequest(user, ContentType.SEASON, UUID.randomUUID(), positionBody(1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[moveEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExistOnMove() throws Exception {
        RegisteredUser user = registerUser("movewatchlistnotfound");

        mockMvc.perform(moveRequest(user, ContentType.MOVIE, UUID.randomUUID(), positionBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Watchlist entry not found"));
    }

    @Test
    @DisplayName("[moveEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUserOnMove() throws Exception {
        RegisteredUser owner = registerUser("movewatchlistowner");
        RegisteredUser intruder = registerUser("movewatchlistintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        WatchlistEntry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(moveRequest(intruder, ContentType.MOVIE, entry.getId(), positionBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Watchlist entry not found"));
    }

    @Test
    @DisplayName("[moveEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForMove() throws Exception {
        RegisteredUser user = registerUser("movewatchlistnoauth");

        mockMvc.perform(patch("/users/me/watchlist/MOVIE/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(positionBody(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[moveEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForMove() throws Exception {
        RegisteredUser user = registerUser("movewatchlistnocsrf");

        mockMvc.perform(patch("/users/me/watchlist/MOVIE/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(positionBody(1)))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /users/me/watchlist/{type}/{watchlistEntryId} ----------

    @Test
    @DisplayName("[removeEntry] Should Return NoContent And Close The Gap - When Entry Exists")
    void shouldReturnNoContentAndCloseTheGapWhenEntryExists() throws Exception {
        RegisteredUser user = registerUser("removewatchlistok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        WatchlistEntry toRemove = persistEntry(entity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);
        Content pulpFiction = persistContent("680", ContentType.MOVIE);
        persistEntry(entity, pulpFiction, ContentType.MOVIE, 2);

        mockMvc.perform(removeRequest(user, ContentType.MOVIE, toRemove.getId()))
                .andExpect(status().isNoContent());

        var entries = watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(entity.getId(), ContentType.MOVIE);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent().getId()).isEqualTo(pulpFiction.getId());
        assertThat(entries.get(0).getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("[removeEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("removewatchlistnotfound");

        mockMvc.perform(removeRequest(user, ContentType.MOVIE, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Watchlist entry not found"));
    }

    @Test
    @DisplayName("[removeEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("removewatchlistowner");
        RegisteredUser intruder = registerUser("removewatchlistintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        WatchlistEntry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE), ContentType.MOVIE, 1);

        mockMvc.perform(removeRequest(intruder, ContentType.MOVIE, entry.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Watchlist entry not found"));

        assertThat(watchlistEntryRepository.findById(entry.getId())).isPresent();
    }

    @Test
    @DisplayName("[removeEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForRemove() throws Exception {
        RegisteredUser user = registerUser("removewatchlistnoauth");

        mockMvc.perform(delete("/users/me/watchlist/MOVIE/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[removeEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForRemove() throws Exception {
        RegisteredUser user = registerUser("removewatchlistnocsrf");

        mockMvc.perform(delete("/users/me/watchlist/MOVIE/" + UUID.randomUUID())
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }
}