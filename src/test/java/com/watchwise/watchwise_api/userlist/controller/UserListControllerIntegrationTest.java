package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        userListRepository.deleteAll();
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

    private MockHttpServletRequestBuilder getUserListsRequest(RegisteredUser viewer, UUID targetUserId) {
        return get("/users/" + targetUserId + "/lists").cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder createRequest(RegisteredUser actor, String body) {
        return post("/users/me/lists")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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

    private String creationBody(String name, String description, Boolean isPublic) {
        String descriptionField = description == null ? "null" : "\"" + description + "\"";
        String isPublicField = isPublic == null ? "null" : String.valueOf(isPublic);
        return """
                {
                    "name": "%s",
                    "description": %s,
                    "isPublic": %s
                }
                """.formatted(name, descriptionField, isPublicField);
    }

    private UserList persistList(User user, String name, boolean isPublic) {
        LocalDateTime now = LocalDateTime.now();
        return userListRepository.save(UserList.builder()
                .user(user)
                .name(name)
                .isPublic(isPublic)
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
    @DisplayName("[getUserLists] Should Return Only Public Lists - When Viewer Is A Different User")
    void shouldReturnOnlyPublicListsWhenViewerIsADifferentUser() throws Exception {
        RegisteredUser viewer = registerUser("getlistsviewer");
        RegisteredUser target = registerUser("getliststarget");
        User targetEntity = userRepository.findById(target.id()).orElseThrow();
        persistList(targetEntity, "Public list", true);
        persistList(targetEntity, "Private list", false);

        mockMvc.perform(getUserListsRequest(viewer, target.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Public list"))
                .andExpect(jsonPath("$.content[0].user.username").value("getliststarget"));
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

    // ---------- POST /users/me/lists ----------

    @Test
    @DisplayName("[createUserList] Should Return Created And Persist The List - When Payload Is Valid")
    void shouldReturnCreatedAndPersistTheListWhenPayloadIsValid() throws Exception {
        RegisteredUser user = registerUser("createlistok");

        mockMvc.perform(createRequest(user, creationBody("Best sci-fi of the 90s", "A curated list", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Best sci-fi of the 90s"))
                .andExpect(jsonPath("$.description").value("A curated list"))
                .andExpect(jsonPath("$.isPublic").value(false))
                .andExpect(jsonPath("$.user.username").value("createlistok"));

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(userListRepository.findByUserId(entity.getId())).hasSize(1);
    }

    @Test
    @DisplayName("[createUserList] Should Default IsPublic To True - When Omitted")
    void shouldDefaultIsPublicToTrueWhenOmitted() throws Exception {
        RegisteredUser user = registerUser("createlistdefault");

        mockMvc.perform(createRequest(user, creationBody("My list", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPublic").value(true));
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

    // ---------- PATCH /lists/{listId} ----------

    @Test
    @DisplayName("[updateUserList] Should Return Ok And Update The Fields - When Payload Is Valid")
    void shouldReturnOkAndUpdateTheFieldsWhenPayloadIsValid() throws Exception {
        RegisteredUser user = registerUser("updatelistok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", true);

        mockMvc.perform(updateRequest(user, list.getId(), creationBody("New name", "New description", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.isPublic").value(false));

        UserList updated = userListRepository.findById(list.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("New name");
    }

    @Test
    @DisplayName("[updateUserList] Should Default IsPublic To True - When Omitted")
    void shouldDefaultIsPublicToTrueWhenOmittedOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatelistdefault");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", false);

        mockMvc.perform(updateRequest(user, list.getId(), creationBody("Old name", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPublic").value(true));
    }

    @Test
    @DisplayName("[updateUserList] Should Return BadRequest - When Name Is Missing")
    void shouldReturnBadRequestWhenNameIsMissingOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatelistnoname");
        User entity = userRepository.findById(user.id()).orElseThrow();
        UserList list = persistList(entity, "Old name", true);

        mockMvc.perform(updateRequest(user, list.getId(), "{ \"description\": \"No name\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateUserList] Should Return NotFound - When List Does Not Exist")
    void shouldReturnNotFoundWhenListDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("updatelistnotfound");

        mockMvc.perform(updateRequest(user, UUID.randomUUID(), creationBody("New name", null, null)))
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

        mockMvc.perform(updateRequest(intruder, list.getId(), creationBody("Hijacked", null, null)))
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
                        .content(creationBody("New name", null, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateUserList] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatelistnocsrf");

        mockMvc.perform(patch("/lists/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creationBody("New name", null, null)))
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