package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
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
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserListItemControllerIntegrationTest {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);

        lenient().when(tmdbClient.getMovieFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        lenient().when(tmdbClient.getTvFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbTvFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        lenient().when(tmdbClient.getEpisodeFullDetails(any(), any(), any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbEpisodeFullDetails(
                        null, null, null, null, null, null, null, null, null)));
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

    private MockHttpServletRequestBuilder addItemRequest(RegisteredUser actor, UUID listId, String body) {
        return post("/lists/" + listId + "/items")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder addItemsBulkRequest(RegisteredUser actor, UUID listId, String body) {
        return post("/lists/" + listId + "/items/bulk")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder removeItemRequest(RegisteredUser actor, UUID listId, UUID itemId) {
        return delete("/lists/" + listId + "/items/" + itemId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder updateItemRequest(RegisteredUser actor, UUID listId, UUID itemId, String body) {
        return patch("/lists/" + listId + "/items/" + itemId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String patchItemBody(Integer position, String description) {
        String positionField = position == null ? "null" : String.valueOf(position);
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        return """
                {
                    "position": %s,
                    "description": %s
                }
                """.formatted(positionField, descriptionField);
    }

    private String contentItemBody(String tmdbId, Integer position, String description) {
        String positionField = position == null ? "null" : String.valueOf(position);
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        return """
                {
                    "content": { "tmdbId": "%s", "type": "MOVIE" },
                    "position": %s,
                    "description": %s
                }
                """.formatted(tmdbId, positionField, descriptionField);
    }

    private String bulkItemsBody(String... tmdbIds) {
        String items = java.util.Arrays.stream(tmdbIds)
                .map(tmdbId -> "{ \"tmdbId\": \"%s\", \"type\": \"MOVIE\" }".formatted(tmdbId))
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {
                    "items": [%s]
                }
                """.formatted(items);
    }

    private String childListItemBody(UUID childListId, Integer position) {
        String positionField = position == null ? "null" : String.valueOf(position);
        return """
                {
                    "childListId": "%s",
                    "position": %s
                }
                """.formatted(childListId, positionField);
    }

    private String contentItemBody(String tmdbId, Integer position, String description, String customPosterUrl) {
        String positionField = position == null ? "null" : String.valueOf(position);
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String posterField = customPosterUrl == null ? "null" : "\"" + customPosterUrl + "\"";
        return """
                {
                    "content": { "tmdbId": "%s", "type": "MOVIE" },
                    "position": %s,
                    "description": %s,
                    "customPosterUrl": %s
                }
                """.formatted(tmdbId, positionField, descriptionField, posterField);
    }

    private String patchItemBody(Integer position, String description, String customPosterUrl) {
        String positionField = position == null ? "null" : String.valueOf(position);
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String posterField = customPosterUrl == null ? "null" : "\"" + customPosterUrl + "\"";
        return """
                {
                    "position": %s,
                    "description": %s,
                    "customPosterUrl": %s
                }
                """.formatted(positionField, descriptionField, posterField);
    }

    private String childListItemBodyWithPoster(UUID childListId, String customPosterUrl) {
        return """
                {
                    "childListId": "%s",
                    "customPosterUrl": "%s"
                }
                """.formatted(childListId, customPosterUrl);
    }

    private String bothProvidedBody(String tmdbId, UUID childListId) {
        return """
                {
                    "content": { "tmdbId": "%s", "type": "MOVIE" },
                    "childListId": "%s"
                }
                """.formatted(tmdbId, childListId);
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

    private Content persistContent(String tmdbId) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UserListItem persistContentItem(UserList userList, Content content, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return userListItemRepository.save(UserListItem.builder()
                .userList(userList)
                .content(content)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private UserListItem persistChildListItem(UserList userList, UserList childList, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return userListItemRepository.save(UserListItem.builder()
                .userList(userList)
                .childList(childList)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ---------- POST /lists/{listId}/items ----------

    @Test
    @DisplayName("[addItem] Should Return Created And Persist The Item - When Content Is Provided")
    void shouldReturnCreatedAndPersistTheItemWhenContentIsProvided() throws Exception {
        RegisteredUser user = registerUser("additemcontent");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, "Great movie")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content.tmdbId").value("550"))
                .andExpect(jsonPath("$.content.type").value("MOVIE"))
                .andExpect(jsonPath("$.childList").doesNotExist())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.description").value("Great movie"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[addItem] Should Return Created And Persist The Item - When ChildListId Is The Caller's Own List")
    void shouldReturnCreatedAndPersistTheItemWhenChildListIdIsTheCallersOwnList() throws Exception {
        RegisteredUser user = registerUser("additemownchild");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList parent = persistList(entity, "List of lists", true);
        UserList child = persistList(entity, "Nested list", true);

        mockMvc.perform(addItemRequest(user, parent.getId(), childListItemBody(child.getId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.childList.id").value(child.getId().toString()))
                .andExpect(jsonPath("$.childList.name").value("Nested list"))
                .andExpect(jsonPath("$.position").value(1));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(parent.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[addItem] Should Return Created - When ChildListId References Another User's Public List")
    void shouldReturnCreatedWhenChildListIdReferencesAnotherUsersPublicList() throws Exception {
        RegisteredUser owner = registerUser("additempublicowner");
        RegisteredUser other = registerUser("additempublicother");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        User otherEntity = userRepository.findById(other.id()).orElseThrow();
        UserList parent = persistList(ownerEntity, "List of lists", true);
        UserList otherPublicList = persistList(otherEntity, "Other's public list", true);

        mockMvc.perform(addItemRequest(owner, parent.getId(), childListItemBody(otherPublicList.getId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.childList.id").value(otherPublicList.getId().toString()))
                .andExpect(jsonPath("$.childList.user.username").value("additempublicother"));
    }

    @Test
    @DisplayName("[addItem] Should Shift Existing Items Forward - When An Explicit Position Is Given")
    void shouldShiftExistingItemsForwardWhenAnExplicitPositionIsGiven() throws Exception {
        RegisteredUser user = registerUser("additemshift");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content first = persistContent("1");
        persistContentItem(list, first, 1);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("2", 1, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1));

        Content second = contentRepository.findByTmdbIdAndType("2", ContentType.MOVIE).orElseThrow();
        var items = userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getContent().getId()).isEqualTo(second.getId());
        assertThat(items.get(0).getPosition()).isEqualTo(1);
        assertThat(items.get(1).getContent().getId()).isEqualTo(first.getId());
        assertThat(items.get(1).getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("[addItem] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("additemnolist");

        mockMvc.perform(addItemRequest(user, UUID.randomUUID(), contentItemBody("550", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[addItem] Should Return NotFound - When List Belongs To A Different User")
    void shouldReturnNotFoundWhenListBelongsToADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("additemowner");
        RegisteredUser intruder = registerUser("additemintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Owner's list", true);

        mockMvc.perform(addItemRequest(intruder, list.getId(), contentItemBody("550", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[addItem] Should Return NotFound - When ChildListId Does Not Exist")
    void shouldReturnNotFoundWhenChildListIdDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("additemnochild");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), childListItemBody(UUID.randomUUID(), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When Neither Content Nor ChildListId Is Provided")
    void shouldReturnBadRequestWhenNeitherContentNorChildListIdIsProvided() throws Exception {
        RegisteredUser user = registerUser("additemneither");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Exactly one of content or childListId must be provided"));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When Both Content And ChildListId Are Provided")
    void shouldReturnBadRequestWhenBothContentAndChildListIdAreProvided() throws Exception {
        RegisteredUser user = registerUser("additemboth");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserList otherList = persistList(entity, "Other list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), bothProvidedBody("550", otherList.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Exactly one of content or childListId must be provided"));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When ChildListId Is The Item's Own List")
    void shouldReturnBadRequestWhenChildListIdIsTheItemsOwnList() throws Exception {
        RegisteredUser user = registerUser("additemself");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), childListItemBody(list.getId(), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A list cannot reference itself"));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When ChildListId Already Contains Nested Lists")
    void shouldReturnBadRequestWhenChildListIdAlreadyContainsNestedLists() throws Exception {
        RegisteredUser user = registerUser("additemdepth");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList grandparent = persistList(entity, "Grandparent", true);
        UserList parent = persistList(entity, "Parent", true);
        UserList child = persistList(entity, "Child", true);
        persistChildListItem(parent, child, 1);

        mockMvc.perform(addItemRequest(user, grandparent.getId(), childListItemBody(parent.getId(), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("nesting depth is limited to one level")));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When List Already Contains Content Items And ChildListId Is Given")
    void shouldReturnBadRequestWhenListAlreadyContainsContentItemsAndChildListIdIsGiven() throws Exception {
        RegisteredUser user = registerUser("additemmixed1");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserList otherList = persistList(entity, "Other list", true);
        Content content = persistContent("550");
        persistContentItem(list, content, 1);

        mockMvc.perform(addItemRequest(user, list.getId(), childListItemBody(otherList.getId(), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot also contain nested lists")));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When List Already Contains Nested Lists And Content Is Given")
    void shouldReturnBadRequestWhenListAlreadyContainsNestedListsAndContentIsGiven() throws Exception {
        RegisteredUser user = registerUser("additemmixed2");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserList childList = persistList(entity, "Child list", true);
        persistChildListItem(list, childList, 1);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot also contain content items")));
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When Position Is Greater Than The Next Free Position")
    void shouldReturnBadRequestWhenPositionIsGreaterThanTheNextFreePosition() throws Exception {
        RegisteredUser user = registerUser("additembadposition");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", 2, null)))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[addItem] Should Return 400 And Leave The List Unchanged - When Content Type Group Conflicts With An Existing Item")
    void shouldReturn400AndLeaveTheListUnchangedWhenContentTypeGroupConflictsWithAnExistingItem() throws Exception {
        RegisteredUser user = registerUser("groupmismatch");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Movies only", true);
        persistContentItem(list, persistContent("550"), 1);

        String episodeBody = """
                {
                    "content": { "type": "EPISODE", "seriesTmdbId": "1396", "seasonNumber": 1, "episodeNumber": 1 }
                }
                """;

        mockMvc.perform(addItemRequest(user, list.getId(), episodeBody))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[addItem] Should Only Let One Content Type Group Win - When Two Different Groups Race On The Same Empty List")
    void shouldOnlyLetOneContentTypeGroupWinWhenTwoDifferentGroupsRaceOnTheSameEmptyList() throws Exception {
        RegisteredUser user = registerUser("grouprace");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Race list", true);

        String movieBody = contentItemBody("550", null, null);
        String episodeBody = """
                {
                    "content": { "type": "EPISODE", "seriesTmdbId": "1396", "seasonNumber": 1, "episodeNumber": 1 }
                }
                """;

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> callMovie = () -> {
            barrier.await();
            return mockMvc.perform(addItemRequest(user, list.getId(), movieBody)).andReturn().getResponse().getStatus();
        };
        Callable<Integer> callEpisode = () -> {
            barrier.await();
            return mockMvc.perform(addItemRequest(user, list.getId(), episodeBody)).andReturn().getResponse().getStatus();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> futureMovie = executor.submit(callMovie);
            Future<Integer> futureEpisode = executor.submit(callEpisode);

            int statusMovie = futureMovie.get(10, TimeUnit.SECONDS);
            int statusEpisode = futureEpisode.get(10, TimeUnit.SECONDS);

            assertThat(List.of(statusMovie, statusEpisode))
                    .containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.BAD_REQUEST.value());
        } finally {
            executor.shutdownNow();
        }

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[addItem] Should Return Forbidden - When ChildListId References Another User's Private List")
    void shouldReturnForbiddenWhenChildListIdReferencesAnotherUsersPrivateList() throws Exception {
        RegisteredUser owner = registerUser("additemprivateowner");
        RegisteredUser other = registerUser("additemprivateother");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        User otherEntity = userRepository.findById(other.id()).orElseThrow();
        UserList parent = persistList(ownerEntity, "List of lists", true);
        UserList otherPrivateList = persistList(otherEntity, "Other's private list", false);

        mockMvc.perform(addItemRequest(owner, parent.getId(), childListItemBody(otherPrivateList.getId(), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This list is private"));
    }

    @Test
    @DisplayName("[addItem] Should Return Conflict - When Content Is Already In The List")
    void shouldReturnConflictWhenContentIsAlreadyInTheList() throws Exception {
        RegisteredUser user = registerUser("additemconflict");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This content is already in the list"));
    }

    @Test
    @DisplayName("[addItem] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnAdd() throws Exception {
        RegisteredUser user = registerUser("additemnoauth");

        mockMvc.perform(post("/lists/" + UUID.randomUUID() + "/items")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentItemBody("550", null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[addItem] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnAdd() throws Exception {
        RegisteredUser user = registerUser("additemnocsrf");

        mockMvc.perform(post("/lists/" + UUID.randomUUID() + "/items")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contentItemBody("550", null, null)))
                .andExpect(status().isForbidden());
    }

    // ---------- POST /lists/{listId}/items/bulk ----------

    @Test
    @DisplayName("[addItems] Should Return Created And Persist All Items In Order - When All Items Are Valid")
    void shouldReturnCreatedAndPersistAllItemsInOrderWhenAllItemsAreValid() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemsBulkRequest(user, list.getId(), bulkItemsBody("550", "551")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].content.tmdbId").value("550"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].content.tmdbId").value("551"))
                .andExpect(jsonPath("$[1].position").value(2));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(2);
    }

    @Test
    @DisplayName("[addItems] Should Append After Existing Items - When List Already Has Items")
    void shouldAppendAfterExistingItemsWhenListAlreadyHasItems() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsappend");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content first = persistContent("1");
        persistContentItem(list, first, 1);

        mockMvc.perform(addItemsBulkRequest(user, list.getId(), bulkItemsBody("550", "551")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].position").value(2))
                .andExpect(jsonPath("$[1].position").value(3));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(3);
    }

    @Test
    @DisplayName("[addItems] Should Return BadRequest And Persist Nothing - When Items Is Empty")
    void shouldReturnBadRequestAndPersistNothingWhenItemsIsEmpty() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsempty");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemsBulkRequest(user, list.getId(), bulkItemsBody()))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[addItems] Should Return Conflict And Persist Nothing - When Two Items In The Payload Point To The Same Content")
    void shouldReturnConflictAndPersistNothingWhenTwoItemsInThePayloadPointToTheSameContent() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsdupe");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemsBulkRequest(user, list.getId(), bulkItemsBody("550", "550")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This content is already in the list"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[addItems] Should Return Conflict And Persist Nothing - When An Item Is Already In The List")
    void shouldReturnConflictAndPersistNothingWhenAnItemIsAlreadyInTheList() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsexisting");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content existing = persistContent("550");
        persistContentItem(list, existing, 1);

        mockMvc.perform(addItemsBulkRequest(user, list.getId(), bulkItemsBody("551", "550")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This content is already in the list"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[addItems] Should Return BadRequest - When List Is Already Locked As A List Of Lists")
    void shouldReturnBadRequestWhenListIsAlreadyLockedAsAListOfLists() throws Exception {
        RegisteredUser user = registerUser("bulkadditemslocked");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserList childList = persistList(entity, "Child list", true);
        persistChildListItem(list, childList, 1);

        mockMvc.perform(addItemsBulkRequest(user, list.getId(), bulkItemsBody("550")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot also contain content items")));
    }

    @Test
    @DisplayName("[addItems] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExistOnBulkAdd() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsnolist");

        mockMvc.perform(addItemsBulkRequest(user, UUID.randomUUID(), bulkItemsBody("550")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[addItems] Should Return NotFound - When List Belongs To A Different User")
    void shouldReturnNotFoundWhenListBelongsToADifferentUserOnBulkAdd() throws Exception {
        RegisteredUser owner = registerUser("bulkadditemsowner");
        RegisteredUser intruder = registerUser("bulkadditemsintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Owner's list", true);

        mockMvc.perform(addItemsBulkRequest(intruder, list.getId(), bulkItemsBody("550")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[addItems] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnBulkAdd() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsnoauth");

        mockMvc.perform(post("/lists/" + UUID.randomUUID() + "/items/bulk")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkItemsBody("550")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[addItems] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnBulkAdd() throws Exception {
        RegisteredUser user = registerUser("bulkadditemsnocsrf");

        mockMvc.perform(post("/lists/" + UUID.randomUUID() + "/items/bulk")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkItemsBody("550")))
                .andExpect(status().isForbidden());
    }

    // ---------- PATCH /lists/{listId}/items/{itemId} ----------

    @Test
    @DisplayName("[updateItem] Should Return Ok And Persist The New Description - When Only Description Is Provided")
    void shouldReturnOkAndPersistTheNewDescriptionWhenOnlyDescriptionIsProvided() throws Exception {
        RegisteredUser user = registerUser("updateitemdescription");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content content = persistContent("550");
        UserListItem item = persistContentItem(list, content, 1);

        mockMvc.perform(updateItemRequest(user, list.getId(), item.getId(), patchItemBody(null, "New description")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.position").value(1));

        UserListItem updated = userListItemRepository.findById(item.getId()).orElseThrow();
        assertThat(updated.getDescription()).isEqualTo("New description");
        assertThat(updated.getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("[updateItem] Should Move Forward And Shift Items Between New And Old Position - When Position Is Earlier")
    void shouldMoveForwardAndShiftItemsBetweenNewAndOldPositionWhenPositionIsEarlier() throws Exception {
        RegisteredUser user = registerUser("updateitemforward");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content content1 = persistContent("1");
        Content content2 = persistContent("2");
        Content content3 = persistContent("3");
        persistContentItem(list, content1, 1);
        persistContentItem(list, content2, 2);
        UserListItem c = persistContentItem(list, content3, 3);

        mockMvc.perform(updateItemRequest(user, list.getId(), c.getId(), patchItemBody(1, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.content.tmdbId").value("3"));

        var items = userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId());
        assertThat(items).extracting(i -> i.getContent().getId()).containsExactly(content3.getId(), content1.getId(), content2.getId());
        assertThat(items).extracting(UserListItem::getPosition).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("[updateItem] Should Move Backward And Shift Items Between Old And New Position - When Position Is Later")
    void shouldMoveBackwardAndShiftItemsBetweenOldAndNewPositionWhenPositionIsLater() throws Exception {
        RegisteredUser user = registerUser("updateitembackward");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content content1 = persistContent("1");
        Content content2 = persistContent("2");
        Content content3 = persistContent("3");
        UserListItem a = persistContentItem(list, content1, 1);
        persistContentItem(list, content2, 2);
        persistContentItem(list, content3, 3);

        mockMvc.perform(updateItemRequest(user, list.getId(), a.getId(), patchItemBody(3, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.content.tmdbId").value("1"));

        var items = userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId());
        assertThat(items).extracting(i -> i.getContent().getId()).containsExactly(content2.getId(), content3.getId(), content1.getId());
        assertThat(items).extracting(UserListItem::getPosition).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("[updateItem] Should Preserve The Item Id - When Moved And Redescribed In The Same Call")
    void shouldPreserveTheItemIdWhenMovedAndRedescribedInTheSameCall() throws Exception {
        RegisteredUser user = registerUser("updateitemcombined");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content content1 = persistContent("1");
        Content content2 = persistContent("2");
        UserListItem a = persistContentItem(list, content1, 1);
        persistContentItem(list, content2, 2);

        mockMvc.perform(updateItemRequest(user, list.getId(), a.getId(), patchItemBody(2, "Moved and redescribed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.getId().toString()))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.description").value("Moved and redescribed"));

        UserListItem updated = userListItemRepository.findById(a.getId()).orElseThrow();
        assertThat(updated.getDescription()).isEqualTo("Moved and redescribed");
        assertThat(updated.getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("[updateItem] Should Return Ok Without Changing Order - When Position Equals The Current Position")
    void shouldReturnOkWithoutChangingOrderWhenPositionEqualsTheCurrentPosition() throws Exception {
        RegisteredUser user = registerUser("updateitemnoop");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content content1 = persistContent("1");
        Content content2 = persistContent("2");
        UserListItem a = persistContentItem(list, content1, 1);
        persistContentItem(list, content2, 2);

        mockMvc.perform(updateItemRequest(user, list.getId(), a.getId(), patchItemBody(1, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));

        var items = userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId());
        assertThat(items).extracting(i -> i.getContent().getId()).containsExactly(content1.getId(), content2.getId());
    }

    @Test
    @DisplayName("[updateItem] Should Return BadRequest - When Position Is Greater Than The Current Item Count")
    void shouldReturnBadRequestWhenPositionIsGreaterThanTheCurrentItemCount() throws Exception {
        RegisteredUser user = registerUser("updateitemtoofar");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserListItem item = persistContentItem(list, persistContent("1"), 1);

        mockMvc.perform(updateItemRequest(user, list.getId(), item.getId(), patchItemBody(2, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateItem] Should Return BadRequest - When Position Is Below One")
    void shouldReturnBadRequestWhenPositionIsBelowOne() throws Exception {
        RegisteredUser user = registerUser("updateitembelowone");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserListItem item = persistContentItem(list, persistContent("1"), 1);

        mockMvc.perform(updateItemRequest(user, list.getId(), item.getId(), patchItemBody(0, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateItem] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExistOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemnolist");

        mockMvc.perform(updateItemRequest(user, UUID.randomUUID(), UUID.randomUUID(), patchItemBody(1, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[updateItem] Should Return NotFound - When List Belongs To A Different User")
    void shouldReturnNotFoundWhenListBelongsToADifferentUserOnUpdate() throws Exception {
        RegisteredUser owner = registerUser("updateitemowner");
        RegisteredUser intruder = registerUser("updateitemintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Owner's list", true);
        UserListItem item = persistContentItem(list, persistContent("550"), 1);

        mockMvc.perform(updateItemRequest(intruder, list.getId(), item.getId(), patchItemBody(null, "Hijacked")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getDescription()).isNull();
    }

    @Test
    @DisplayName("[updateItem] Should Return NotFound - When Item Does Not Exist")
    void shouldReturnNotFoundWhenItemDoesNotExistOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemnoitem");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(updateItemRequest(user, list.getId(), UUID.randomUUID(), patchItemBody(1, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List item not found"));
    }

    @Test
    @DisplayName("[updateItem] Should Return NotFound - When Item Belongs To A Different List")
    void shouldReturnNotFoundWhenItemBelongsToADifferentListOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemwronglist");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList listA = persistList(entity, "List A", true);
        UserList listB = persistList(entity, "List B", true);
        UserListItem item = persistContentItem(listB, persistContent("550"), 1);

        mockMvc.perform(updateItemRequest(user, listA.getId(), item.getId(), patchItemBody(null, "Wrong list")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List item not found"));

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getDescription()).isNull();
    }

    @Test
    @DisplayName("[updateItem] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemnoauth");

        mockMvc.perform(patch("/lists/" + UUID.randomUUID() + "/items/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchItemBody(1, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateItem] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemnocsrf");

        mockMvc.perform(patch("/lists/" + UUID.randomUUID() + "/items/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchItemBody(1, null)))
                .andExpect(status().isForbidden());
    }

    // ---------- DELETE /lists/{listId}/items/{itemId} ----------

    @Test
    @DisplayName("[removeItem] Should Return NoContent, Remove The Item And Close The Position Gap")
    void shouldReturnNoContentRemoveTheItemAndClosePositionGap() throws Exception {
        RegisteredUser user = registerUser("removeitemok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        Content first = persistContent("1");
        Content second = persistContent("2");
        UserListItem item1 = persistContentItem(list, first, 1);
        UserListItem item2 = persistContentItem(list, second, 2);

        mockMvc.perform(removeItemRequest(user, list.getId(), item1.getId()))
                .andExpect(status().isNoContent());

        assertThat(userListItemRepository.findById(item1.getId())).isEmpty();
        UserListItem remaining = userListItemRepository.findById(item2.getId()).orElseThrow();
        assertThat(remaining.getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("[removeItem] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExistOnRemove() throws Exception {
        RegisteredUser user = registerUser("removeitemnolist");

        mockMvc.perform(removeItemRequest(user, UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));
    }

    @Test
    @DisplayName("[removeItem] Should Return NotFound - When List Belongs To A Different User")
    void shouldReturnNotFoundWhenListBelongsToADifferentUserOnRemove() throws Exception {
        RegisteredUser owner = registerUser("removeitemowner");
        RegisteredUser intruder = registerUser("removeitemintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Owner's list", true);
        Content content = persistContent("550");
        UserListItem item = persistContentItem(list, content, 1);

        mockMvc.perform(removeItemRequest(intruder, list.getId(), item.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List not found"));

        assertThat(userListItemRepository.findById(item.getId())).isPresent();
    }

    @Test
    @DisplayName("[removeItem] Should Return NotFound - When Item Does Not Exist")
    void shouldReturnNotFoundWhenItemDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("removeitemnoitem");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(removeItemRequest(user, list.getId(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List item not found"));
    }

    @Test
    @DisplayName("[removeItem] Should Return NotFound - When Item Belongs To A Different List")
    void shouldReturnNotFoundWhenItemBelongsToADifferentList() throws Exception {
        RegisteredUser user = registerUser("removeitemwronglist");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList listA = persistList(entity, "List A", true);
        UserList listB = persistList(entity, "List B", true);
        Content content = persistContent("550");
        UserListItem item = persistContentItem(listB, content, 1);

        mockMvc.perform(removeItemRequest(user, listA.getId(), item.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("List item not found"));

        assertThat(userListItemRepository.findById(item.getId())).isPresent();
    }

    @Test
    @DisplayName("[removeItem] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentOnRemove() throws Exception {
        RegisteredUser user = registerUser("removeitemnoauth");

        mockMvc.perform(delete("/lists/" + UUID.randomUUID() + "/items/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[removeItem] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingOnRemove() throws Exception {
        RegisteredUser user = registerUser("removeitemnocsrf");

        mockMvc.perform(delete("/lists/" + UUID.randomUUID() + "/items/" + UUID.randomUUID())
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ---------- customPosterUrl ----------

    @Test
    @DisplayName("[addItem] Should Persist CustomPosterUrl - When Content Item Provides One")
    void shouldPersistCustomPosterUrlWhenContentItemProvidesOne() throws Exception {
        RegisteredUser user = registerUser("additemposter");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, null, "https://example.com/poster.png")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/poster.png"));

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId()).get(0).getCustomPosterUrl())
                .isEqualTo("https://example.com/poster.png");
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When CustomPosterUrl Is Provided With ChildListId")
    void shouldReturnBadRequestWhenCustomPosterUrlIsProvidedWithChildListId() throws Exception {
        RegisteredUser user = registerUser("additemposterchild");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList parent = persistList(entity, "Parent", true);
        UserList child = persistList(entity, "Child", true);

        mockMvc.perform(addItemRequest(user, parent.getId(), childListItemBodyWithPoster(child.getId(), "https://example.com/x.png")))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(parent.getId())).isEmpty();
    }

    @Test
    @DisplayName("[updateItem] Should Change CustomPosterUrl - When A Different Value Is Provided")
    void shouldChangeCustomPosterUrlWhenADifferentValueIsProvidedOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemposter");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserListItem item = persistContentItem(list, persistContent("550"), 1);

        mockMvc.perform(updateItemRequest(user, list.getId(), item.getId(), patchItemBody(null, null, "https://example.com/new.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customPosterUrl").value("https://example.com/new.png"));

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getCustomPosterUrl())
                .isEqualTo("https://example.com/new.png");
    }

    @Test
    @DisplayName("[updateItem] Should Return BadRequest - When CustomPosterUrl Is Provided Against A ChildList Item")
    void shouldReturnBadRequestWhenCustomPosterUrlIsProvidedAgainstAChildListItemOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemposterchild");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList parent = persistList(entity, "Parent", true);
        UserList child = persistList(entity, "Child", true);
        UserListItem item = persistChildListItem(parent, child, 1);

        mockMvc.perform(updateItemRequest(user, parent.getId(), item.getId(), patchItemBody(null, null, "https://example.com/x.png")))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }

    @Test
    @DisplayName("[addItem] Should Return BadRequest - When CustomPosterUrl Is Not A Valid Url")
    void shouldReturnBadRequestWhenCustomPosterUrlIsNotAValidUrlOnAdd() throws Exception {
        RegisteredUser user = registerUser("additemposterinvalid");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);

        mockMvc.perform(addItemRequest(user, list.getId(), contentItemBody("550", null, null, "not-a-url")))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(list.getId())).isEmpty();
    }

    @Test
    @DisplayName("[updateItem] Should Return BadRequest - When CustomPosterUrl Is Not A Valid Url")
    void shouldReturnBadRequestWhenCustomPosterUrlIsNotAValidUrlOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updateitemposterinvalid");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "My list", true);
        UserListItem item = persistContentItem(list, persistContent("550"), 1);

        mockMvc.perform(updateItemRequest(user, list.getId(), item.getId(), patchItemBody(null, null, "not-a-url")))
                .andExpect(status().isBadRequest());

        assertThat(userListItemRepository.findById(item.getId()).orElseThrow().getCustomPosterUrl()).isNull();
    }
}
