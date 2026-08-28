package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.entity.Like;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserListControllerIntegrationTest {

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
    private UserListRepository userListRepository;

    @Autowired
    private UserListItemRepository userListItemRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        likeRepository.deleteAll();
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
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

    private void makeProfilePrivate(RegisteredUser user) throws Exception {
        mockMvc.perform(patch("/users/me")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"isProfilePublic\": false }"))
                .andExpect(status().isOk());
    }

    private void persistAcceptedFollow(UUID followerId, UUID followedId) {
        User followerUser = userRepository.findById(followerId).orElseThrow();
        User followedUser = userRepository.findById(followedId).orElseThrow();
        followerRepository.save(Follower.builder()
                .follower(followerUser)
                .followed(followedUser)
                .status(FollowStatus.ACCEPTED)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private MockHttpServletRequestBuilder getUserListsRequest(RegisteredUser viewer, UUID targetUserId) {
        return get("/users/" + targetUserId + "/lists").cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder getUserListByIdRequest(RegisteredUser viewer, UUID listId) {
        return get("/lists/" + listId).cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder getLikedListsRequest(RegisteredUser viewer) {
        return get("/users/me/liked-lists").cookie(viewer.accessToken());
    }

    private Like persistLike(User user, UserList list) {
        return likeRepository.save(Like.builder()
                .user(user)
                .list(list)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private MockHttpServletRequestBuilder createRequest(RegisteredUser actor, String body) {
        return post("/users/me/lists")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder createBulkRequest(RegisteredUser actor, String body) {
        return post("/users/me/lists/bulk")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String bulkCreationBody(String name, String description, UserListVisibility visibility, String itemsJson) {
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String visibilityField = visibility == null ? "null" : "\"" + visibility + "\"";
        return """
                {
                    "name": "%s",
                    "description": %s,
                    "visibility": %s,
                    "items": %s
                }
                """.formatted(name, descriptionField, visibilityField, itemsJson);
    }

    private String contentRefJson(String tmdbId, String type) {
        return """
                { "tmdbId": "%s", "type": "%s" }
                """.formatted(tmdbId, type);
    }

    private MockHttpServletRequestBuilder updateRequest(RegisteredUser actor, UUID listId, String body) {
        return patch("/lists/" + listId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser actor, UUID listId) {
        return delete("/lists/" + listId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private String creationBody(String name, String description, UserListVisibility visibility) {
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String visibilityField = visibility == null ? "null" : "\"" + visibility + "\"";
        return """
                {
                    "name": "%s",
                    "description": %s,
                    "visibility": %s
                }
                """.formatted(name, descriptionField, visibilityField);
    }

    private String patchBody(String name, String description, UserListVisibility visibility) {
        String nameField = name == null ? "null" : "\"" + name + "\"";
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String visibilityField = visibility == null ? "null" : "\"" + visibility + "\"";
        return """
                {
                    "name": %s,
                    "description": %s,
                    "visibility": %s
                }
                """.formatted(nameField, descriptionField, visibilityField);
    }

    private UserList persistList(User user, String name, boolean isPublic) {
        return persistList(user, name, isPublic ? UserListVisibility.PUBLIC : UserListVisibility.PRIVATE);
    }

    private UserList persistList(User user, String name, UserListVisibility visibility) {
        LocalDateTime now = LocalDateTime.now();
        return userListRepository.save(UserList.builder()
                .user(user)
                .name(name)
                .visibility(visibility)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UserListItem persistContentItem(UserList list, String tmdbId, int position) {
        LocalDateTime now = LocalDateTime.now();
        Content content = contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return userListItemRepository.save(UserListItem.builder()
                .userList(list)
                .content(content)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UserListItem persistNestedListItem(UserList list, UserList childList, int position) {
        LocalDateTime now = LocalDateTime.now();
        return userListItemRepository.save(UserListItem.builder()
                .userList(list)
                .childList(childList)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ---------- GET /users/{userId}/lists ----------

    @Test
    @DisplayName("[getUserLists] Should Return All Lists Including Private - When Viewer Is The Owner")
    void shouldReturnAllListsIncludingPrivateWhenViewerIsTheOwner() throws Exception {
        RegisteredUser user = registerUser("getlistsowner");
        User entity = userRepository.findById(user.id()).orElseThrow();
        persistList(entity, "Public list", true);
        persistList(entity, "Private list", false);

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Only Public Lists - When Viewer Does Not Follow Target")
    void shouldReturnOnlyPublicListsWhenViewerDoesNotFollowTarget() throws Exception {
        RegisteredUser viewer = registerUser("getlistsviewer");
        RegisteredUser target = registerUser("getliststarget");
        User targetEntity = userRepository.findById(target.id()).orElseThrow();
        persistList(targetEntity, "Public list", UserListVisibility.PUBLIC);
        persistList(targetEntity, "Followers-only list", UserListVisibility.FOLLOWERS);
        persistList(targetEntity, "Private list", UserListVisibility.PRIVATE);

        mockMvc.perform(getUserListsRequest(viewer, target.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Public list"))
                .andExpect(jsonPath("$.content[0].user").doesNotExist());
    }

    @Test
    @DisplayName("[getUserLists] Should Return Public And Followers-Only Lists - When Viewer Follows Target")
    void shouldReturnPublicAndFollowersOnlyListsWhenViewerFollowsTarget() throws Exception {
        RegisteredUser viewer = registerUser("getlistsfollower");
        RegisteredUser target = registerUser("getlistsfollowed");
        User targetEntity = userRepository.findById(target.id()).orElseThrow();
        persistList(targetEntity, "Public list", UserListVisibility.PUBLIC);
        persistList(targetEntity, "Followers-only list", UserListVisibility.FOLLOWERS);
        persistList(targetEntity, "Private list", UserListVisibility.PRIVATE);
        persistAcceptedFollow(viewer.id(), target.id());

        mockMvc.perform(getUserListsRequest(viewer, target.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[*].name")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("Public list", "Followers-only list")));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Forbidden - When Target Profile Is Private And Viewer Does Not Follow")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerDoesNotFollow() throws Exception {
        RegisteredUser viewer = registerUser("getlistsprivateviewer");
        RegisteredUser target = registerUser("getlistsprivatetarget");
        makeProfilePrivate(target);

        mockMvc.perform(getUserListsRequest(viewer, target.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Ok - When Target Profile Is Private But Viewer Follows Target")
    void shouldReturnOkWhenTargetProfileIsPrivateButViewerFollowsTarget() throws Exception {
        RegisteredUser viewer = registerUser("getlistsprivatefollower");
        RegisteredUser target = registerUser("getlistsprivatefollowed");
        persistAcceptedFollow(viewer.id(), target.id());
        makeProfilePrivate(target);

        mockMvc.perform(getUserListsRequest(viewer, target.id()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getUserLists] Should Return Empty Content - When User Has No Lists")
    void shouldReturnEmptyContentWhenUserHasNoLists() throws Exception {
        RegisteredUser user = registerUser("getlistsempty");

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("[getUserLists] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("getlistsnotfound");

        mockMvc.perform(getUserListsRequest(viewer, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getUserLists] Should Include Up To 5 Content Items Ordered By Position - When List Has More Than 5")
    void shouldIncludeUpToFiveContentItemsOrderedByPositionWhenListHasMoreThanFive() throws Exception {
        RegisteredUser user = registerUser("getlistspreview");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        for (int i = 1; i <= 6; i++) {
            persistContentItem(list, "tmdb-" + i, i);
        }

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].previewItems.length()").value(5))
                .andExpect(jsonPath("$.content[0].previewItems[0].tmdbId").value("tmdb-1"))
                .andExpect(jsonPath("$.content[0].previewItems[4].tmdbId").value("tmdb-5"))
                .andExpect(jsonPath("$.content[0].nestedListsCount").value(0));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Empty Preview And Nested Lists Count - When List Has No Items")
    void shouldReturnEmptyPreviewAndNestedListsCountWhenListHasNoItems() throws Exception {
        RegisteredUser user = registerUser("getlistspreviewempty");
        User entity = userRepository.findById(user.id()).orElseThrow();
        persistList(entity, "My list", true);

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].previewItems.length()").value(0))
                .andExpect(jsonPath("$.content[0].nestedListsCount").value(0));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Empty Preview And Nested Lists Count - When List Only Has Nested Lists")
    void shouldReturnEmptyPreviewAndNestedListsCountWhenListOnlyHasNestedLists() throws Exception {
        RegisteredUser user = registerUser("getlistspreviewnested");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList listOfLists = persistList(entity, "List of lists", true);

        RegisteredUser childOwner = registerUser("getlistspreviewnestedchild");
        User childOwnerEntity = userRepository.findById(childOwner.id()).orElseThrow();
        UserList childA = persistList(childOwnerEntity, "Nested list A", true);
        UserList childB = persistList(childOwnerEntity, "Nested list B", true);
        persistNestedListItem(listOfLists, childA, 1);
        persistNestedListItem(listOfLists, childB, 2);

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].previewItems.length()").value(0))
                .andExpect(jsonPath("$.content[0].nestedListsCount").value(2));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Zero Watched Percentage - When No List Items Were Logged")
    void shouldReturnZeroWatchedPercentageWhenNoListItemsWereLogged() throws Exception {
        RegisteredUser user = registerUser("getlistswatchedzero");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        persistContentItem(list, "550", 1);
        persistContentItem(list, "680", 2);

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].watchedPercentage").value(0.0));
    }

    @Test
    @DisplayName("[getUserLists] Should Reflect Logged Content In Watched Percentage - When A List Item Is Logged Via DiaryEntry")
    void shouldReflectLoggedContentInWatchedPercentageWhenAListItemIsLoggedViaDiaryEntry() throws Exception {
        RegisteredUser user = registerUser("getlistswatchedsome");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        persistContentItem(list, "550", 1);
        persistContentItem(list, "680", 2);

        mockMvc.perform(post("/diary")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"content\": { \"tmdbId\": \"550\", \"type\": \"MOVIE\" } }"))
                .andExpect(status().isCreated());

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].watchedPercentage").value(50.0));
    }

    @Test
    @DisplayName("[getUserLists] Should Not Double Count A Rewatch In Watched Percentage")
    void shouldNotDoubleCountARewatchInWatchedPercentage() throws Exception {
        RegisteredUser user = registerUser("getlistswatchedrewatch");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        persistContentItem(list, "550", 1);

        String body = "{ \"content\": { \"tmdbId\": \"550\", \"type\": \"MOVIE\" }, \"isRewatch\": %s }";
        mockMvc.perform(post("/diary")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("null")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/diary")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("true")))
                .andExpect(status().isCreated());

        mockMvc.perform(getUserListsRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].watchedPercentage").value(100.0));
    }

    @Test
    @DisplayName("[getUserLists] Should Paginate Results - When Page And Size Are Provided")
    void shouldPaginateResultsWhenPageAndSizeAreProvided() throws Exception {
        RegisteredUser user = registerUser("getlistspage");
        User entity = userRepository.findById(user.id()).orElseThrow();
        persistList(entity, "List 1", true);
        persistList(entity, "List 2", true);

        mockMvc.perform(getUserListsRequest(user, user.id()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("[getUserLists] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGet() throws Exception {
        RegisteredUser user = registerUser("getlistsnoauth");

        mockMvc.perform(get("/users/" + user.id() + "/lists"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET /users/me/liked-lists ----------

    @Test
    @DisplayName("[getLikedLists] Should Return Only The Lists The Viewer Liked - When Called")
    void shouldReturnOnlyTheListsTheViewerLikedWhenCalled() throws Exception {
        RegisteredUser viewer = registerUser("likedlistsviewer");
        RegisteredUser owner = registerUser("likedlistsowner");
        User viewerEntity = userRepository.findById(viewer.id()).orElseThrow();
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList likedList = persistList(ownerEntity, "Liked list", true);
        persistList(ownerEntity, "Not liked list", true);
        persistLike(viewerEntity, likedList);

        mockMvc.perform(getLikedListsRequest(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Liked list"));
    }

    @Test
    @DisplayName("[getLikedLists] Should Return Empty Content - When The Viewer Has Liked No List")
    void shouldReturnEmptyContentWhenTheViewerHasLikedNoList() throws Exception {
        RegisteredUser viewer = registerUser("likedlistsempty");

        mockMvc.perform(getLikedListsRequest(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("[getLikedLists] Should Return Lists Most Recently Liked First - When The Viewer Liked Multiple Lists")
    void shouldReturnListsMostRecentlyLikedFirstWhenTheViewerLikedMultipleLists() throws Exception {
        RegisteredUser viewer = registerUser("likedlistsorder");
        RegisteredUser owner = registerUser("likedlistsorderowner");
        User viewerEntity = userRepository.findById(viewer.id()).orElseThrow();
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList olderLiked = persistList(ownerEntity, "Older liked", true);
        UserList newerLiked = persistList(ownerEntity, "Newer liked", true);
        Like olderLike = persistLike(viewerEntity, olderLiked);
        olderLike.setCreatedAt(LocalDateTime.now().minusDays(1));
        likeRepository.save(olderLike);
        persistLike(viewerEntity, newerLiked);

        mockMvc.perform(getLikedListsRequest(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Newer liked"))
                .andExpect(jsonPath("$.content[1].name").value("Older liked"));
    }

    @Test
    @DisplayName("[getLikedLists] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForLikedLists() throws Exception {
        mockMvc.perform(get("/users/me/liked-lists"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET /lists/{listId} ----------

    @Test
    @DisplayName("[getUserListById] Should Return The List With Its Items - When Viewer Is The Owner")
    void shouldReturnTheListWithItsItemsWhenViewerIsTheOwner() throws Exception {
        RegisteredUser user = registerUser("getbyidowner");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", UserListVisibility.PRIVATE);

        mockMvc.perform(getUserListByIdRequest(user, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My list"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("[getUserListById] Should Reflect Logged Content In Watched Percentage - When A List Item Is Logged Via DiaryEntry")
    void shouldReflectLoggedContentInWatchedPercentageWhenAListItemIsLoggedViaDiaryEntryOnGetById() throws Exception {
        RegisteredUser user = registerUser("getbyidwatched");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", UserListVisibility.PUBLIC);
        persistContentItem(list, "550", 1);
        persistContentItem(list, "680", 2);

        mockMvc.perform(getUserListByIdRequest(user, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchedPercentage").value(0.0));

        mockMvc.perform(post("/diary")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"content\": { \"tmdbId\": \"550\", \"type\": \"MOVIE\" } }"))
                .andExpect(status().isCreated());

        mockMvc.perform(getUserListByIdRequest(user, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchedPercentage").value(50.0));
    }

    @Test
    @DisplayName("[getUserListById] Should Populate Watched Percentage From The Viewer's Own History - When Viewer Is A Different User From The Owner")
    void shouldPopulateWatchedPercentageFromTheViewersOwnHistoryWhenViewerIsADifferentUserFromTheOwner() throws Exception {
        RegisteredUser owner = registerUser("getbyidwatchedowner");
        RegisteredUser viewer = registerUser("getbyidwatchedviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Public list", UserListVisibility.PUBLIC);
        persistContentItem(list, "550", 1);
        persistContentItem(list, "680", 2);

        mockMvc.perform(post("/diary")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"content\": { \"tmdbId\": \"550\", \"type\": \"MOVIE\" } }"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/diary")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"content\": { \"tmdbId\": \"680\", \"type\": \"MOVIE\" } }"))
                .andExpect(status().isCreated());

        mockMvc.perform(getUserListByIdRequest(owner, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchedPercentage").value(100.0));

        mockMvc.perform(getUserListByIdRequest(viewer, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchedPercentage").value(0.0));
    }

    @Test
    @DisplayName("[getUserListById] Should Return The List - When List Is Public And Viewer Is A Different User")
    void shouldReturnTheListWhenListIsPublicAndViewerIsADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("getbyidpublicowner");
        RegisteredUser viewer = registerUser("getbyidpublicviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Public list", UserListVisibility.PUBLIC);

        mockMvc.perform(getUserListByIdRequest(viewer, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Public list"));
    }

    @Test
    @DisplayName("[getUserListById] Should Return The List - When Owner's Profile Is Private But The List Itself Is Public")
    void shouldReturnTheListWhenOwnersProfileIsPrivateButTheListItselfIsPublic() throws Exception {
        RegisteredUser owner = registerUser("getbyidprivateprofile");
        RegisteredUser viewer = registerUser("getbyidprivateprofileviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Public list", UserListVisibility.PUBLIC);
        makeProfilePrivate(owner);

        mockMvc.perform(getUserListByIdRequest(viewer, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Public list"));
    }

    @Test
    @DisplayName("[getUserListById] Should Return The List - When List Is Followers-Only And Viewer Follows The Owner")
    void shouldReturnTheListWhenListIsFollowersOnlyAndViewerFollowsTheOwner() throws Exception {
        RegisteredUser owner = registerUser("getbyidfollowersowner");
        RegisteredUser viewer = registerUser("getbyidfollowersviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Followers-only list", UserListVisibility.FOLLOWERS);
        persistAcceptedFollow(viewer.id(), owner.id());

        mockMvc.perform(getUserListByIdRequest(viewer, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Followers-only list"));
    }

    @Test
    @DisplayName("[getUserListById] Should Return Forbidden - When List Is Followers-Only And Viewer Does Not Follow The Owner")
    void shouldReturnForbiddenWhenListIsFollowersOnlyAndViewerDoesNotFollowTheOwner() throws Exception {
        RegisteredUser owner = registerUser("getbyidforbiddenfollowers");
        RegisteredUser viewer = registerUser("getbyidforbiddenfollowersviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Followers-only list", UserListVisibility.FOLLOWERS);

        mockMvc.perform(getUserListByIdRequest(viewer, list.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This list is private"));
    }

    @Test
    @DisplayName("[getUserListById] Should Return Forbidden - When List Is Private And Viewer Is A Different User")
    void shouldReturnForbiddenWhenListIsPrivateAndViewerIsADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("getbyidforbiddenprivate");
        RegisteredUser viewer = registerUser("getbyidforbiddenprivateviewer");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Private list", UserListVisibility.PRIVATE);

        mockMvc.perform(getUserListByIdRequest(viewer, list.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This list is private"));
    }

    @Test
    @DisplayName("[getUserListById] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExistOnGetById() throws Exception {
        RegisteredUser user = registerUser("getbyidnotfound");

        mockMvc.perform(getUserListByIdRequest(user, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[getUserListById] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGetById() throws Exception {
        mockMvc.perform(get("/lists/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- POST /users/me/lists ----------

    @Test
    @DisplayName("[createUserList] Should Return Created And Persist The List - When Payload Is Valid")
    void shouldReturnCreatedAndPersistTheListWhenPayloadIsValid() throws Exception {
        RegisteredUser user = registerUser("createlistok");

        mockMvc.perform(createRequest(user, creationBody("Best sci-fi of the 90s", "A curated list", UserListVisibility.PRIVATE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Best sci-fi of the 90s"))
                .andExpect(jsonPath("$.description").value("A curated list"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.user").doesNotExist());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(userListRepository.findByUserId(entity.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[createUserList] Should Default Visibility To Public - When Omitted")
    void shouldDefaultVisibilityToPublicWhenOmitted() throws Exception {
        RegisteredUser user = registerUser("createlistdefault");

        mockMvc.perform(createRequest(user, creationBody("My list", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("[createUserList] Should Return BadRequest And Not Persist - When Name Is Missing")
    void shouldReturnBadRequestAndNotPersistWhenNameIsMissing() throws Exception {
        RegisteredUser user = registerUser("createlistnoname");

        mockMvc.perform(createRequest(user, "{ \"description\": \"No name here\" }"))
                .andExpect(status().isBadRequest());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(userListRepository.findByUserId(entity.getId())).isEmpty();
    }

    @Test
    @DisplayName("[createUserList] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForCreate() throws Exception {
        RegisteredUser user = registerUser("createlistnoauth");

        mockMvc.perform(post("/users/me/lists")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creationBody("My list", null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createUserList] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForCreate() throws Exception {
        RegisteredUser user = registerUser("createlistnocsrf");

        mockMvc.perform(post("/users/me/lists")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creationBody("My list", null, null)))
                .andExpect(status().isForbidden());
    }

    // ---------- POST /users/me/lists/bulk ----------

    @Test
    @DisplayName("[createUserListWithItems] Should Return Created And Persist The List With Items In Order - When Payload Is Valid")
    void shouldReturnCreatedAndPersistTheListWithItemsInOrderWhenPayloadIsValid() throws Exception {
        RegisteredUser user = registerUser("bulklistok");
        String items = "[" + contentRefJson("100", "MOVIE") + "," + contentRefJson("200", "SERIES") + "]";

        MvcResult result = mockMvc.perform(createBulkRequest(user, bulkCreationBody("My bulk list", "Movies and series", UserListVisibility.PUBLIC, items)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My bulk list"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].content.tmdbId").value("100"))
                .andExpect(jsonPath("$.items[0].position").value(1))
                .andExpect(jsonPath("$.items[1].content.tmdbId").value("200"))
                .andExpect(jsonPath("$.items[1].position").value(2))
                .andReturn();

        String listId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(UUID.fromString(listId))).hasSize(2);
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Default Visibility To Public - When Omitted")
    void shouldDefaultVisibilityToPublicWhenOmittedOnBulkCreate() throws Exception {
        RegisteredUser user = registerUser("bulklistdefault");
        String items = "[" + contentRefJson("100", "MOVIE") + "]";

        mockMvc.perform(createBulkRequest(user, bulkCreationBody("My list", null, null, items)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Return BadRequest And Not Persist - When Items Is Empty")
    void shouldReturnBadRequestAndNotPersistWhenItemsIsEmpty() throws Exception {
        RegisteredUser user = registerUser("bulklistempty");

        mockMvc.perform(createBulkRequest(user, bulkCreationBody("My list", null, UserListVisibility.PUBLIC, "[]")))
                .andExpect(status().isBadRequest());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(userListRepository.findByUserId(entity.getId())).isEmpty();
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Return BadRequest And Not Persist - When Name Is Missing")
    void shouldReturnBadRequestAndNotPersistWhenNameIsMissingOnBulkCreate() throws Exception {
        RegisteredUser user = registerUser("bulklistnoname");
        String body = "{ \"items\": [" + contentRefJson("100", "MOVIE") + "] }";

        mockMvc.perform(createBulkRequest(user, body))
                .andExpect(status().isBadRequest());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(userListRepository.findByUserId(entity.getId())).isEmpty();
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Return Conflict And Not Persist The List - When The Same Content Appears Twice")
    void shouldReturnConflictAndNotPersistTheListWhenTheSameContentAppearsTwice() throws Exception {
        RegisteredUser user = registerUser("bulklistduplicate");
        String items = "[" + contentRefJson("100", "MOVIE") + "," + contentRefJson("100", "MOVIE") + "]";

        mockMvc.perform(createBulkRequest(user, bulkCreationBody("My list", null, UserListVisibility.PUBLIC, items)))
                .andExpect(status().isConflict());

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(userListRepository.findByUserId(entity.getId())).isEmpty();
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForBulkCreate() throws Exception {
        RegisteredUser user = registerUser("bulklistnoauth");
        String items = "[" + contentRefJson("100", "MOVIE") + "]";

        mockMvc.perform(post("/users/me/lists/bulk")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkCreationBody("My list", null, UserListVisibility.PUBLIC, items)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForBulkCreate() throws Exception {
        RegisteredUser user = registerUser("bulklistnocsrf");
        String items = "[" + contentRefJson("100", "MOVIE") + "]";

        mockMvc.perform(post("/users/me/lists/bulk")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkCreationBody("My list", null, UserListVisibility.PUBLIC, items)))
                .andExpect(status().isForbidden());
    }

    // ---------- PATCH /lists/{listId} ----------

    @Test
    @DisplayName("[updateUserList] Should Return Ok And Update The Fields - When Payload Is Valid")
    void shouldReturnOkAndUpdateTheFieldsWhenPayloadIsValid() throws Exception {
        RegisteredUser user = registerUser("updatelistok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", true);

        mockMvc.perform(updateRequest(user, list.getId(), patchBody("New name", "New description", UserListVisibility.PRIVATE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));

        UserList updated = userListRepository.findById(list.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("New name");
    }

    @Test
    @DisplayName("[updateUserList] Should Only Update Description - When Name And Visibility Are Omitted")
    void shouldOnlyUpdateDescriptionWhenNameAndVisibilityAreOmitted() throws Exception {
        RegisteredUser user = registerUser("updatelistdesconly");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", UserListVisibility.PRIVATE);

        mockMvc.perform(updateRequest(user, list.getId(), patchBody(null, "New description", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Old name"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));

        UserList updated = userListRepository.findById(list.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Old name");
        assertThat(updated.getVisibility()).isEqualTo(UserListVisibility.PRIVATE);
    }

    @Test
    @DisplayName("[updateUserList] Should Not Change Any Field - When Payload Is Empty")
    void shouldNotChangeAnyFieldWhenPayloadIsEmpty() throws Exception {
        RegisteredUser user = registerUser("updatelistempty");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", UserListVisibility.PRIVATE);

        mockMvc.perform(updateRequest(user, list.getId(), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Old name"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("[updateUserList] Should Return BadRequest - When Name Is Blank")
    void shouldReturnBadRequestWhenNameIsBlankOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatelistblankname");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", true);

        mockMvc.perform(updateRequest(user, list.getId(), patchBody("   ", null, null)))
                .andExpect(status().isBadRequest());

        UserList untouched = userListRepository.findById(list.getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("Old name");
    }

    @Test
    @DisplayName("[updateUserList] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("updatelistnotfound");

        mockMvc.perform(updateRequest(user, UUID.randomUUID(), patchBody("New name", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[updateUserList] Should Return NotFound - When List Belongs To A Different User")
    void shouldReturnNotFoundWhenListBelongsToADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("updatelistowner");
        RegisteredUser intruder = registerUser("updatelistintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Owner's list", true);

        mockMvc.perform(updateRequest(intruder, list.getId(), patchBody("Hijacked", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        UserList untouched = userListRepository.findById(list.getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("Owner's list");
    }

    @Test
    @DisplayName("[updateUserList] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatelistnoauth");

        mockMvc.perform(patch("/lists/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody("New name", null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateUserList] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatelistnocsrf");

        mockMvc.perform(patch("/lists/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody("New name", null, null)))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /lists/{listId} ----------

    @Test
    @DisplayName("[deleteUserList] Should Return NoContent And Remove The List - When Owned By The User")
    void shouldReturnNoContentAndRemoveTheListWhenOwnedByTheUser() throws Exception {
        RegisteredUser user = registerUser("deletelistok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(deleteRequest(user, list.getId()))
                .andExpect(status().isNoContent());

        assertThat(userListRepository.findById(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteUserList] Should Close The Position Gap In The Parent List - When The Deleted List Is Nested As A Child Item")
    void shouldCloseThePositionGapInTheParentListWhenTheDeletedListIsNestedAsAChildItem() throws Exception {
        RegisteredUser owner = registerUser("deletenestedlistowner");
        RegisteredUser childOwner = registerUser("deletenestedlistchildowner");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        User childOwnerEntity = userRepository.findById(childOwner.id()).orElseThrow();
        UserList listOfLists = persistList(ownerEntity, "List of lists", true);
        UserList childA = persistList(childOwnerEntity, "Nested A", true);
        UserList childB = persistList(childOwnerEntity, "Nested B", true);
        UserList childC = persistList(childOwnerEntity, "Nested C", true);
        persistNestedListItem(listOfLists, childA, 1);
        UserListItem itemB = persistNestedListItem(listOfLists, childB, 2);
        persistNestedListItem(listOfLists, childC, 3);

        mockMvc.perform(deleteRequest(childOwner, childB.getId()))
                .andExpect(status().isNoContent());

        assertThat(userListRepository.findById(childB.getId())).isEmpty();
        assertThat(userListItemRepository.findById(itemB.getId())).isEmpty();
        List<UserListItem> remaining = userListItemRepository.findByUserListIdOrderByPositionAsc(listOfLists.getId());
        assertThat(remaining).extracting(item -> item.getChildList().getId(), UserListItem::getPosition)
                .containsExactly(
                        tuple(childA.getId(), 1),
                        tuple(childC.getId(), 2));
    }

    @Test
    @DisplayName("[deleteUserList] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExistOnDelete() throws Exception {
        RegisteredUser user = registerUser("deletelistnotfound");

        mockMvc.perform(deleteRequest(user, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[deleteUserList] Should Return NotFound - When List Belongs To A Different User")
    void shouldReturnNotFoundWhenListBelongsToADifferentUserOnDelete() throws Exception {
        RegisteredUser owner = registerUser("deletelistowner");
        RegisteredUser intruder = registerUser("deletelistintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Owner's list", true);

        mockMvc.perform(deleteRequest(intruder, list.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        assertThat(userListRepository.findById(list.getId())).isPresent();
    }

    @Test
    @DisplayName("[deleteUserList] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForDelete() throws Exception {
        RegisteredUser user = registerUser("deletelistnoauth");

        mockMvc.perform(delete("/lists/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[deleteUserList] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForDelete() throws Exception {
        RegisteredUser user = registerUser("deletelistnocsrf");

        mockMvc.perform(delete("/lists/" + UUID.randomUUID())
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }
}
