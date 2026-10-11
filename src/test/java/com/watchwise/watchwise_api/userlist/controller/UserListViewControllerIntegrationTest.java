package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadataKey;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
    @Autowired CommentRepository comments;
    @Autowired ContentRepository contents;
    @Autowired LikeRepository likes;
    @Autowired FollowerRepository followers;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RequestThrottler requestThrottler;

    @MockitoBean TmdbClient tmdbClient;

    @BeforeEach
    void cleanDatabase() {
        likes.deleteAll();
        comments.deleteAll();
        items.deleteAll();
        lists.deleteAll();
        contents.deleteAll();
        followers.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    @DisplayName("[getMyListView] Should Return A Deduplicated Visual Page Envelope - When Source Is All")
    void shouldReturnDeduplicatedVisualPageEnvelopeWhenSourceIsAll() throws Exception {
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
    @DisplayName("[getDiscoverListView] Should Omit Nonpublic Lists And Accept Public Sorts - When Discovering Lists")
    void shouldOmitNonpublicListsAndAcceptPublicSortsWhenDiscoveringLists() throws Exception {
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
    @DisplayName("[getDiscoverListView] Should Accept Omitted And Blank Queries - When Searching Lists")
    void shouldAcceptOmittedAndBlankQueriesWhenSearchingLists() throws Exception {
        RegisteredUser viewer = register("viewqueryviewer");
        RegisteredUser owner = register("viewqueryowner");
        persistList(users.findById(owner.id()).orElseThrow(), "Visible", UserListVisibility.PUBLIC);

        mockMvc.perform(discoverView(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(discoverView(viewer).param("q", "   ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("[getDiscoverListView] Should Require Authentication And Reject Invalid Query Values - When Calling Visual Routes")
    void shouldRequireAuthenticationAndRejectInvalidQueryValuesWhenCallingVisualRoutes() throws Exception {
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
    @DisplayName("[getUserLists] Should Keep The Existing DTO Shape - When Calling A Raw List Route")
    void shouldKeepTheExistingDtoShapeWhenCallingARawListRoute() throws Exception {
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
    @DisplayName("[getUserListView] Should Apply Item Type And Genre Filters - When Reading User Lists")
    void shouldApplyItemTypeAndGenreFiltersWhenReadingUserLists() throws Exception {
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

    @Test
    @DisplayName("[getUserListView] Should Filter Every Content Type And Nested Lists - When Item Type Is Selected")
    void shouldFilterEveryContentTypeAndNestedListsWhenItemTypeIsSelected() throws Exception {
        RegisteredUser viewer = register("viewtypesviewer");
        RegisteredUser owner = register("viewtypesowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList movie = persistList(ownerEntity, "Movie only", UserListVisibility.PUBLIC);
        UserList series = persistList(ownerEntity, "Series only", UserListVisibility.PUBLIC);
        UserList season = persistList(ownerEntity, "Season only", UserListVisibility.PUBLIC);
        UserList episode = persistList(ownerEntity, "Episode only", UserListVisibility.PUBLIC);
        UserList nested = persistList(ownerEntity, "Nested only", UserListVisibility.PUBLIC);
        persistItem(movie, ContentType.MOVIE, null);
        persistItem(series, ContentType.SERIES, null);
        persistItem(season, ContentType.SEASON, null);
        persistItem(episode, ContentType.EPISODE, null);
        UserList child = persistList(ownerEntity, "Child list", UserListVisibility.PUBLIC);
        persistNestedItem(nested, child, 1);

        for (var expected : List.of(
                new Object[]{"MOVIE", movie}, new Object[]{"SERIES", series},
                new Object[]{"SEASON", season}, new Object[]{"EPISODE", episode},
                new Object[]{"LIST", nested})) {
            String itemType = (String) expected[0];
            UserList expectedList = (UserList) expected[1];
            mockMvc.perform(userView(viewer, owner.id()).param("itemType", itemType))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(expectedList.getId().toString()));
        }
    }

    @Test
    @DisplayName("[getDiscoverListView] Should Apply Name Likes Item And Comment Ordering In Both Directions - When Multiple Public Lists Exist")
    void shouldApplyNameLikesItemAndCommentOrderingInBothDirectionsWhenMultiplePublicListsExist() throws Exception {
        RegisteredUser viewer = register("vieworderviewer");
        RegisteredUser owner = register("vieworderowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList alpha = persistList(ownerEntity, "Alpha", UserListVisibility.PUBLIC, 3);
        UserList beta = persistList(ownerEntity, "Beta", UserListVisibility.PUBLIC, 8);
        persistItem(alpha, ContentType.MOVIE, null);
        persistItem(beta, ContentType.MOVIE, null);
        persistItem(beta, ContentType.MOVIE, null);
        persistComment(alpha, ownerEntity, "Alpha comment");
        persistComment(beta, ownerEntity, "Beta comment one");
        persistComment(beta, ownerEntity, "Beta comment two");

        mockMvc.perform(discoverView(viewer).param("sortBy", "name").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Beta"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "name").param("sortDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Beta"))
                .andExpect(jsonPath("$.content[1].name").value("Alpha"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "likesCount").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Beta"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "likesCount").param("sortDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Beta"))
                .andExpect(jsonPath("$.content[1].name").value("Alpha"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "itemsCount").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Beta"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "itemsCount").param("sortDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Beta"))
                .andExpect(jsonPath("$.content[1].name").value("Alpha"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "commentsCount").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Beta"));
        mockMvc.perform(discoverView(viewer).param("sortBy", "commentsCount").param("sortDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Beta"))
                .andExpect(jsonPath("$.content[1].name").value("Alpha"));
    }

    @Test
    @DisplayName("[getMyListView] Should Omit A Private Nested Child And Keep A Later Public Child - When Assembling Previews")
    void shouldOmitPrivateNestedChildAndKeepLaterPublicChildWhenAssemblingPreviews() throws Exception {
        RegisteredUser owner = register("viewnestedowner");
        RegisteredUser childOwner = register("viewnestedchildowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        User childOwnerEntity = users.findById(childOwner.id()).orElseThrow();
        UserList parent = persistList(ownerEntity, "Parent", UserListVisibility.PUBLIC);
        UserList privateChild = persistList(childOwnerEntity, "Private child", UserListVisibility.PRIVATE);
        UserList publicChild = persistList(childOwnerEntity, "Public child", UserListVisibility.PUBLIC);
        persistNestedItem(parent, privateChild, 1);
        persistNestedItem(parent, publicChild, 2);

        mockMvc.perform(myView(owner).param("source", "OWNED")
                        .param("sortBy", "name").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(parent.getId().toString()))
                .andExpect(jsonPath("$.content[0].nestedListPreviews.length()").value(1))
                .andExpect(jsonPath("$.content[0].nestedListPreviews[0].id").value(publicChild.getId().toString()))
                .andExpect(jsonPath("$.content[0].nestedListPreviews[0].name").value("Public child"));
    }

    @Test
    @DisplayName("[getMyListView] Should Return An Unavailable Preview Card - When TMDB Metadata Cannot Be Loaded")
    void shouldReturnAnUnavailablePreviewCardWhenTmdbMetadataCannotBeLoaded() throws Exception {
        RegisteredUser owner = register("viewtmdbowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "With content", UserListVisibility.PUBLIC);
        persistItem(list, ContentType.MOVIE, null);
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), eq("en-US")))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        mockMvc.perform(myView(owner).param("source", "OWNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].previewCards.length()").value(1))
                .andExpect(jsonPath("$.content[0].previewCards[0].previewStatus").value("UNAVAILABLE"));
    }

    @Test
    @DisplayName("[getMyListView] Should Return A Partial Preview Card - When TMDB Returns Incomplete Metadata")
    void shouldReturnAPartialPreviewCardWhenTmdbReturnsIncompleteMetadata() throws Exception {
        RegisteredUser owner = register("viewpartialowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList list = persistList(ownerEntity, "Partial card", UserListVisibility.PUBLIC);
        persistItem(list, ContentType.MOVIE, null);
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), eq("en-US")))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbCardMetadata("Known title", null, null, null)));

        mockMvc.perform(myView(owner).param("source", "OWNED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].previewCards.length()").value(1))
                .andExpect(jsonPath("$.content[0].previewCards[0].previewStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.content[0].previewCards[0].title").value("Known title"));
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
        return persistList(user, name, visibility, 0);
    }

    private UserList persistList(User user, String name, UserListVisibility visibility, int likesCount) {
        LocalDateTime now = LocalDateTime.now();
        return lists.save(UserList.builder().user(user).name(name).visibility(visibility)
                .likesCount(likesCount).createdAt(now).updatedAt(now).build());
    }

    private void persistItem(UserList list, ContentType type, String genre) {
        LocalDateTime now = LocalDateTime.now();
        String tmdbId = UUID.randomUUID().toString().substring(0, 20);
        String seriesTmdbId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Content.ContentBuilder contentBuilder = Content.builder().type(type)
                .genres(genre == null ? null : List.of(genre)).createdAt(now).updatedAt(now);
        if (type == ContentType.MOVIE || type == ContentType.SERIES) {
            contentBuilder.tmdbId(tmdbId);
        } else {
            contentBuilder.seriesTmdbId(seriesTmdbId).seasonNumber(2);
            if (type == ContentType.EPISODE) {
                contentBuilder.episodeNumber(3);
            }
        }
        Content content = contents.save(contentBuilder.build());
        int position = items.findByUserListIdOrderByPositionAsc(list.getId()).size() + 1;
        items.save(UserListItem.builder().userList(list).content(content).position(position)
                .createdAt(now).updatedAt(now).build());
    }

    private void persistComment(UserList list, User user, String text) {
        LocalDateTime now = LocalDateTime.now();
        comments.save(Comment.builder().user(user).list(list).text(text)
                .createdAt(now).updatedAt(now).build());
    }

    private void persistNestedItem(UserList parent, UserList child, int position) {
        LocalDateTime now = LocalDateTime.now();
        items.save(UserListItem.builder().userList(parent).childList(child).position(position)
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
