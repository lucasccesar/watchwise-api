package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.watchwise_test.WatchwiseIntegrationTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class UserListItemOrderControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserBlockRepository userBlockRepository;
    @Autowired private UserListRepository userListRepository;
    @Autowired private UserListItemRepository userListItemRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RequestThrottler requestThrottler;
    @Autowired private TestEmailConfirmationSender emailConfirmationSender;

    @MockitoBean private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        contentRepository.deleteAll();
        userBlockRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        emailConfirmationSender.clear();
        RequestThrottlerTestSupport.reset(requestThrottler);
        lenient().when(tmdbClient.getMovieFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null)));
        lenient().when(tmdbClient.getCardMetadata(any(), anyString()))
                .thenReturn(new TmdbLookupResult.Unavailable<>());
    }

    @Test
    void shouldReadItemOrderInBatchesAndMoveAcrossTheLoadedRange() throws Exception {
        RegisteredUser owner = registerUser("itemorder-owner");
        User user = userRepository.findById(owner.id()).orElseThrow();
        UserList list = userListRepository.saveAndFlush(UserList.builder()
                .user(user).name("Movies").visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        List<UserListItem> items = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int index = 1; index <= 41; index++) {
            Content content = contentRepository.save(Content.builder()
                    .tmdbId("order-movie-" + index).type(ContentType.MOVIE)
                    .createdAt(now.plusSeconds(index)).updatedAt(now.plusSeconds(index)).build());
            items.add(UserListItem.builder().userList(list).content(content).position(index)
                    .createdAt(now.plusSeconds(index)).updatedAt(now.plusSeconds(index)).build());
        }
        userListItemRepository.saveAllAndFlush(items);

        MvcResult firstPage = mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(30))
                .andExpect(jsonPath("$.items[0].position").value(1))
                .andExpect(jsonPath("$.items[0].movable").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String staleCursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
        mockMvc.perform(patch("/lists/" + list.getId() + "/items/" + items.getFirst().getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()).param("cursor", staleCursor))
                .andExpect(status().isConflict());

        MvcResult refreshedFirstPage = mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk()).andReturn();
        String revision = com.jayway.jsonpath.JsonPath.read(
                refreshedFirstPage.getResponse().getContentAsString(), "$.revision");
        String cursor = com.jayway.jsonpath.JsonPath.read(
                refreshedFirstPage.getResponse().getContentAsString(), "$.nextCursor");
        mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()).param("size", "31"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()).param("cursor", "x".repeat(201)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()).param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(11))
                .andExpect(jsonPath("$.items[0].position").value(31));

        UUID absentId = UUID.randomUUID();
        String invalidBatch = """
                {"revision":"%s","moves":[
                  {"movedId":"%s","anchorId":"%s","side":"AFTER"},
                  {"movedId":"%s","anchorId":"%s","side":"AFTER"}
                ]}
                """.formatted(revision, items.get(30).getId(), items.get(4).getId(), absentId,
                items.get(4).getId());
        mockMvc.perform(patch("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(invalidBatch))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                items.get(30).getId())).isEqualTo(31);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                items.get(4).getId())).isEqualTo(5);

        String moveBody = """
                {"revision":"%s","moves":[{"movedId":"%s","anchorId":"%s","side":"AFTER"}]}
                """.formatted(revision, items.get(30).getId(), items.get(4).getId());
        MvcResult moved = mockMvc.perform(patch("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody))
                .andExpect(status().isOk()).andReturn();
        String newRevision = com.jayway.jsonpath.JsonPath.read(moved.getResponse().getContentAsString(), "$.revision");
        assertThat(newRevision).isNotEqualTo(revision);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                items.get(30).getId())).isEqualTo(6);

        mockMvc.perform(patch("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldInvalidateItemCursorsAfterAddingOrRemovingAnItem() throws Exception {
        RegisteredUser owner = registerUser("itemorder-mutation-owner");
        User user = userRepository.findById(owner.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        UserList list = userListRepository.saveAndFlush(UserList.builder()
                .user(user).name("Mutation list").visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(now).updatedAt(now).build());
        List<UserListItem> initialItems = new ArrayList<>();
        for (int position = 1; position <= 2; position++) {
            Content content = contentRepository.save(Content.builder()
                    .tmdbId("mutation-movie-" + position).type(ContentType.MOVIE)
                    .createdAt(now.plusSeconds(position)).updatedAt(now.plusSeconds(position)).build());
            initialItems.add(UserListItem.builder().userList(list).content(content).position(position)
                    .createdAt(now.plusSeconds(position)).updatedAt(now.plusSeconds(position)).build());
        }
        userListItemRepository.saveAllAndFlush(initialItems);

        String cursorBeforeAdd = cursorAfterFirstListItem(owner, list.getId());
        mockMvc.perform(post("/lists/" + list.getId() + "/items")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":{\"tmdbId\":\"mutation-movie-added\",\"type\":\"MOVIE\"}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()).param("cursor", cursorBeforeAdd))
                .andExpect(status().isConflict());

        String cursorBeforeRemove = cursorAfterFirstListItem(owner, list.getId());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/lists/" + list.getId() + "/items/" + initialItems.get(1).getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/lists/" + list.getId() + "/items/order")
                        .cookie(owner.accessToken()).param("cursor", cursorBeforeRemove))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRedactInaccessibleNestedListItemsAndRejectThemAsMoveTargets() throws Exception {
        RegisteredUser owner = registerUser("itemorder-parent-owner");
        RegisteredUser childOwner = registerUser("itemorder-child-owner");
        UserList parent = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(owner.id()).orElseThrow()).name("Parent")
                .visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList child = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(childOwner.id()).orElseThrow()).name("Secret child")
                .visibility(UserListVisibility.PRIVATE).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList publicChild = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(childOwner.id()).orElseThrow()).name("Visible child")
                .visibility(UserListVisibility.PUBLIC).rank(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem nested = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(child).position(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem anchor = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(publicChild).position(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        MvcResult result = mockMvc.perform(get("/lists/" + parent.getId() + "/items/order")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].label").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.items[0].movable").value(false))
                .andReturn();
        mockMvc.perform(get("/lists/" + parent.getId() + "/items/order")
                        .cookie(childOwner.accessToken()))
                .andExpect(status().isNotFound());
        String revision = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.revision");
        String body = """
                {"revision":"%s","moves":[{"movedId":"%s","anchorId":"%s","side":"AFTER"}]}
                """.formatted(revision, nested.getId(), anchor.getId());

        mockMvc.perform(patch("/lists/" + parent.getId() + "/items/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectMovingVisibleItemsAcrossAnInaccessibleNestedList() throws Exception {
        RegisteredUser owner = registerUser("itemorder-barrier-owner");
        RegisteredUser childOwner = registerUser("itemorder-barrier-child-owner");
        UserList parent = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(owner.id()).orElseThrow()).name("Parent")
                .visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList beforeList = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(childOwner.id()).orElseThrow()).name("Visible before")
                .visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList privateList = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(childOwner.id()).orElseThrow()).name("Hidden barrier")
                .visibility(UserListVisibility.PRIVATE).rank(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList afterList = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(childOwner.id()).orElseThrow()).name("Visible after")
                .visibility(UserListVisibility.PUBLIC).rank(3)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem visibleBefore = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(beforeList).position(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem hidden = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(privateList).position(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem visibleAfter = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(afterList).position(3)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        MvcResult page = mockMvc.perform(get("/lists/" + parent.getId() + "/items/order")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].movable").value(true))
                .andExpect(jsonPath("$.items[1].movable").value(false))
                .andExpect(jsonPath("$.items[2].movable").value(true))
                .andReturn();
        String revision = com.jayway.jsonpath.JsonPath.read(page.getResponse().getContentAsString(), "$.revision");
        String moveAcrossBarrier = """
                {"revision":"%s","moves":[{"movedId":"%s","anchorId":"%s","side":"AFTER"}]}
                """.formatted(revision, visibleBefore.getId(), visibleAfter.getId());

        mockMvc.perform(patch("/lists/" + parent.getId() + "/items/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(moveAcrossBarrier))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                visibleBefore.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                hidden.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                visibleAfter.getId())).isEqualTo(3);
    }

    @Test
    void shouldRejectMovingVisibleItemsAcrossABlockedNestedList() throws Exception {
        RegisteredUser owner = registerUser("itemorder-block-barrier-owner");
        RegisteredUser childOwner = registerUser("itemorder-block-barrier-child-owner");
        User parentOwner = userRepository.findById(owner.id()).orElseThrow();
        User nestedOwner = userRepository.findById(childOwner.id()).orElseThrow();
        UserList parent = userListRepository.saveAndFlush(UserList.builder()
                .user(parentOwner).name("Parent")
                .visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList beforeList = userListRepository.saveAndFlush(UserList.builder()
                .user(nestedOwner).name("Visible before")
                .visibility(UserListVisibility.PUBLIC).rank(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList blockedList = userListRepository.saveAndFlush(UserList.builder()
                .user(nestedOwner).name("Blocked list")
                .visibility(UserListVisibility.PUBLIC).rank(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserList afterList = userListRepository.saveAndFlush(UserList.builder()
                .user(nestedOwner).name("Visible after")
                .visibility(UserListVisibility.PUBLIC).rank(3)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        userBlockRepository.saveAndFlush(UserBlock.builder().blocker(parentOwner).blocked(nestedOwner)
                .createdAt(LocalDateTime.now()).build());
        UserListItem visibleBefore = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(beforeList).position(1)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem blocked = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(blockedList).position(2)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        UserListItem visibleAfter = userListItemRepository.saveAndFlush(UserListItem.builder()
                .userList(parent).childList(afterList).position(3)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());

        MvcResult page = mockMvc.perform(get("/lists/" + parent.getId() + "/items/order")
                        .cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].movable").value(false))
                .andReturn();
        String revision = com.jayway.jsonpath.JsonPath.read(page.getResponse().getContentAsString(), "$.revision");
        String moveAcrossBarrier = """
                {"revision":"%s","moves":[{"movedId":"%s","anchorId":"%s","side":"AFTER"}]}
                """.formatted(revision, visibleBefore.getId(), visibleAfter.getId());

        mockMvc.perform(patch("/lists/" + parent.getId() + "/items/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(moveAcrossBarrier))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                visibleBefore.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                blocked.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT position FROM user_list_items WHERE id = ?", Integer.class,
                visibleAfter.getId())).isEqualTo(3);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) { }

    private String cursorAfterFirstListItem(RegisteredUser owner, UUID listId) throws Exception {
        MvcResult result = mockMvc.perform(get("/lists/" + listId + "/items/order")
                        .cookie(owner.accessToken()).param("size", "1"))
                .andExpect(status().isOk()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.nextCursor");
    }

    private RegisteredUser registerUser(String username) throws Exception {
        MvcResult registration = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123"}
                                """.formatted(username, username, username)))
                .andExpect(status().isAccepted()).andReturn();
        Cookie proof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        MvcResult confirmation = mockMvc.perform(post("/auth/confirm-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + emailConfirmationSender.tokenFor(username + "@email.com") + "\"}")
                        .cookie(proof))
                .andExpect(status().isNoContent()).andReturn();
        Cookie access = confirmation.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrf = confirmation.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), access, csrf);
    }
}
