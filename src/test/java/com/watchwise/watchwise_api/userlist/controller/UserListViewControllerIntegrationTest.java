package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserListViewControllerIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired UserListRepository lists;
    @Autowired UserListItemRepository items;
    @Autowired ContentRepository contents;
    @Autowired LikeRepository likes;
    @Autowired FollowerRepository followers;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RequestThrottler requestThrottler;

    @MockitoBean TmdbClient tmdbClient;

    @BeforeEach
    void cleanDatabase() {
        likes.deleteAll();
        items.deleteAll();
        lists.deleteAll();
        contents.deleteAll();
        followers.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    @DisplayName("My source ALL returns a deduplicated visual page envelope")
    void sourceAllReturnsDeduplicatedPageEnvelope() throws Exception {
        RegisteredUser owner = register("viewallowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Shared list", UserListVisibility.PUBLIC);
        likes.save(Like.builder().user(ownerEntity).list(list).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(myView(owner).param("source", "ALL").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Shared list"))
                .andExpect(jsonPath("$.content[0].user.id").value(owner.id().toString()))
                .andExpect(jsonPath("$.content[0].user.username").value("viewallowner"))
                .andExpect(jsonPath("$.content[0].user.name").value("viewallowner"))
                .andExpect(jsonPath("$.content[0].user.profilePicture").value("https://default-image.png"))
                .andExpect(jsonPath("$.content[0].user.isProfilePublic").value(true))
                .andExpect(jsonPath("$.content[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.content[0].likesCount").value(0))
                .andExpect(jsonPath("$.content[0].itemsCount").value(0))
                .andExpect(jsonPath("$.content[0].commentsCount").value(0))
                .andExpect(jsonPath("$.content[0].previewCards").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Discovery omits private and follower-only lists and accepts each public sort")
    void discoverHidesNonPublicListsAndAcceptsPublicSorts() throws Exception {
        RegisteredUser viewer = register("viewdiscoverviewer");
        RegisteredUser owner = register("viewdiscoverowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        persistList(ownerEntity, "Visible public", UserListVisibility.PUBLIC);
        persistList(ownerEntity, "Hidden private", UserListVisibility.PRIVATE);
        persistList(ownerEntity, "Hidden followers", UserListVisibility.FOLLOWERS);

        for (String sort : List.of("name", "likesCount", "itemsCount", "commentsCount")) {
            mockMvc.perform(discoverView(viewer).param("sortBy", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Visible public"));
        }
    }

    @Test
    @DisplayName("Discovery accepts omitted and whitespace-only queries")
    void discoverAcceptsOmittedAndWhitespaceQuery() throws Exception {
        RegisteredUser viewer = register("viewqueryviewer");
        RegisteredUser owner = register("viewqueryowner");
        persistList(users.findById(owner.id()).orElseThrow(), "Visible", UserListVisibility.PUBLIC);

        mockMvc.perform(discoverView(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(discoverView(viewer).param("q", "   ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Visual routes require authentication and reject invalid query values as ApiError")
    void visualRoutesRequireAuthenticationAndReturnApiErrorsForInvalidParameters() throws Exception {
        mockMvc.perform(get("/lists/discover/view"))
                .andExpect(status().isUnauthorized());

        RegisteredUser viewer = register("viewinvalidviewer");
        for (MockHttpServletRequestBuilder invalid : List.of(
                discoverView(viewer).param("itemType", "INVALID"),
                discoverView(viewer).param("page", "-1"),
                discoverView(viewer).param("size", "9"),
                myView(viewer).param("source", "INVALID"),
                discoverView(viewer).param("sortDirection", "up"))) {
            mockMvc.perform(invalid)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.path").exists());
        }
    }

    @Test
    @DisplayName("Existing raw list endpoints continue returning their original DTO shape")
    void rawListRoutesKeepTheirExistingDtos() throws Exception {
        RegisteredUser owner = register("viewrawowner");
        RegisteredUser viewer = register("viewrawviewer");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Raw list", UserListVisibility.PUBLIC);

        mockMvc.perform(get("/users/" + owner.id() + "/lists").cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(list.getId().toString()))
                .andExpect(jsonPath("$.content[0].name").value("Raw list"));
    }

    @Test
    @DisplayName("User view forwards item type and genre filters")
    void userViewAppliesItemTypeAndGenreFilters() throws Exception {
        RegisteredUser viewer = register("viewfilterviewer");
        RegisteredUser owner = register("viewfilterowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList movieList = persistList(ownerEntity, "Drama movie", UserListVisibility.PUBLIC);
        UserList seriesList = persistList(ownerEntity, "Drama series", UserListVisibility.PUBLIC);
        UserList otherGenreList = persistList(ownerEntity, "Comedy series", UserListVisibility.PUBLIC);
        persistItem(movieList, ContentType.MOVIE, "Drama");
        persistItem(seriesList, ContentType.SERIES, "Drama");
        persistItem(otherGenreList, ContentType.SERIES, "Comedy");

        mockMvc.perform(userView(viewer, owner.id()).param("itemType", "SERIES").param("genre", "Drama"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Drama series"));
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {}

    private RegisteredUser register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123"}
                                """.formatted(username, username, username)))
                .andExpect(status().isCreated()).andReturn();
        User user = users.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE),
                result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    private UserList persistList(User user, String name, UserListVisibility visibility) {
        LocalDateTime now = LocalDateTime.now();
        return lists.save(UserList.builder().user(user).name(name).visibility(visibility)
                .createdAt(now).updatedAt(now).build());
    }

    private void persistItem(UserList list, ContentType type, String genre) {
        LocalDateTime now = LocalDateTime.now();
        String tmdbId = UUID.randomUUID().toString().substring(0, 20);
        Content content = contents.save(Content.builder().type(type)
                .tmdbId(tmdbId)
                .genres(List.of(genre)).createdAt(now).updatedAt(now).build());
        items.save(UserListItem.builder().userList(list).content(content).position(1)
                .createdAt(now).updatedAt(now).build());
    }

    private MockHttpServletRequestBuilder myView(RegisteredUser viewer) {
        return get("/users/me/lists/view").cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder userView(RegisteredUser viewer, UUID userId) {
        return get("/users/" + userId + "/lists/view").cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder discoverView(RegisteredUser viewer) {
        return get("/lists/discover/view").cookie(viewer.accessToken());
    }
}
