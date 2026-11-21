package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = com.watchwise_test.WatchwiseIntegrationTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class UserListOrderControllerIntegrationTest {

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
    @Autowired private UserListRepository userListRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RequestThrottler requestThrottler;
    @Autowired private TestEmailConfirmationSender emailConfirmationSender;

    @BeforeEach
    void setUp() {
        userListRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        emailConfirmationSender.clear();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    void shouldReadOwnedListsInBatchesAndMoveAcrossTheLoadedRange() throws Exception {
        RegisteredUser owner = registerUser("listorder-owner");
        List<UserList> lists = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int index = 1; index <= 31; index++) {
            lists.add(UserList.builder()
                    .user(userRepository.findById(owner.id()).orElseThrow())
                    .name("List " + index)
                    .visibility(UserListVisibility.PUBLIC)
                    .rank(index)
                    .createdAt(now.plusSeconds(index))
                    .updatedAt(now.plusSeconds(index))
                    .build());
        }
        userListRepository.saveAllAndFlush(lists);

        MvcResult firstPage = mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(30))
                .andExpect(jsonPath("$.items[0].position").value(1))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()).param("size", "31"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()).param("cursor", "x".repeat(201)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()).param("cursor", " ".repeat(201)))
                .andExpect(status().isBadRequest());
        String revision = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.revision");
        String cursor = com.jayway.jsonpath.JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()).param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].position").value(31))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        UUID first = lists.getFirst().getId();
        UUID third = lists.get(2).getId();
        String oversizedRevisionBody = """
                {"revision":"99999999999999999999","moves":[{"movedId":"%s","anchorId":"%s","side":"AFTER"}]}
                """.formatted(first, third);
        mockMvc.perform(patch("/users/me/lists/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(oversizedRevisionBody))
                .andExpect(status().isBadRequest());
        UUID fifth = lists.get(4).getId();
        String duplicateMoves = """
                {"revision":"%s","moves":[
                  {"movedId":"%s","anchorId":"%s","side":"AFTER"},
                  {"movedId":"%s","anchorId":"%s","side":"AFTER"}
                ]}
                """.formatted(revision, first, third, first, fifth);
        mockMvc.perform(patch("/users/me/lists/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(duplicateMoves))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT rank FROM user_lists WHERE id = ?", Integer.class, first))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT rank FROM user_lists WHERE id = ?", Integer.class, third))
                .isEqualTo(3);

        String moveBody = """
                {"revision":"%s","moves":[{"movedId":"%s","anchorId":"%s","side":"AFTER"}]}
                """.formatted(revision, first, third);
        MvcResult moved = mockMvc.perform(patch("/users/me/lists/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody))
                .andExpect(status().isOk())
                .andReturn();
        String newRevision = com.jayway.jsonpath.JsonPath.read(moved.getResponse().getContentAsString(), "$.revision");
        assertThat(newRevision).isNotEqualTo(revision);
        assertThat(jdbcTemplate.queryForObject("SELECT rank FROM user_lists WHERE id = ?", Integer.class, first))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT rank FROM user_lists WHERE id = ?", Integer.class, third))
                .isEqualTo(2);

        mockMvc.perform(patch("/users/me/lists/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(moveBody))
                .andExpect(status().isConflict());

        String repeatedAnchorMoves = """
                {"revision":"%s","moves":[
                  {"movedId":"%s","anchorId":"%s","side":"AFTER"},
                  {"movedId":"%s","anchorId":"%s","side":"AFTER"}
                ]}
                """.formatted(newRevision, lists.get(1).getId(), third, lists.get(3).getId(), third);
        mockMvc.perform(patch("/users/me/lists/order")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(repeatedAnchorMoves))
                .andExpect(status().isOk());

        MvcResult beforeExistingRoute = mockMvc.perform(get("/users/me/lists/order")
                        .cookie(owner.accessToken()).param("size", "2"))
                .andExpect(status().isOk()).andReturn();
        String existingRouteCursor = com.jayway.jsonpath.JsonPath.read(
                beforeExistingRoute.getResponse().getContentAsString(), "$.nextCursor");
        mockMvc.perform(patch("/lists/" + lists.get(5).getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rank\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken())
                        .param("cursor", existingRouteCursor).param("size", "2"))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldInvalidateListCursorsAfterCreatingOrDeletingAList() throws Exception {
        RegisteredUser owner = registerUser("listorder-mutation-owner");
        User user = userRepository.findById(owner.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        List<UserList> lists = userListRepository.saveAllAndFlush(List.of(
                UserList.builder().user(user).name("First").visibility(UserListVisibility.PUBLIC).rank(1)
                        .createdAt(now).updatedAt(now).build(),
                UserList.builder().user(user).name("Second").visibility(UserListVisibility.PUBLIC).rank(2)
                        .createdAt(now.plusSeconds(1)).updatedAt(now.plusSeconds(1)).build()));

        String cursorBeforeCreate = cursorAfterFirstOwnedList(owner);
        mockMvc.perform(post("/users/me/lists")
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Created\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()).param("cursor", cursorBeforeCreate))
                .andExpect(status().isConflict());

        String cursorBeforeDelete = cursorAfterFirstOwnedList(owner);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/lists/" + lists.get(1).getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/users/me/lists/order").cookie(owner.accessToken()).param("cursor", cursorBeforeDelete))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldReturnOnlyTheAuthenticatedUsersOwnCollection() throws Exception {
        RegisteredUser owner = registerUser("listorder-other-owner");
        RegisteredUser viewer = registerUser("listorder-viewer");
        LocalDateTime now = LocalDateTime.now();
        userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(owner.id()).orElseThrow()).name("Other owner's list")
                .visibility(UserListVisibility.PUBLIC).rank(1).createdAt(now).updatedAt(now).build());
        UserList viewerList = userListRepository.saveAndFlush(UserList.builder()
                .user(userRepository.findById(viewer.id()).orElseThrow()).name("Viewer's list")
                .visibility(UserListVisibility.PUBLIC).rank(1).createdAt(now).updatedAt(now).build());

        mockMvc.perform(get("/users/me/lists/order").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(viewerList.getId().toString()));
        assertThat(userRepository.findById(owner.id())).isPresent();
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) { }

    private String cursorAfterFirstOwnedList(RegisteredUser owner) throws Exception {
        MvcResult result = mockMvc.perform(get("/users/me/lists/order")
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
