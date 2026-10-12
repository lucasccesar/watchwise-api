package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadataKey;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleEpisode;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailViewResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListDetailViewService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserListDetailViewControllerIntegrationTest {

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
    @Autowired DiaryEntryRepository diaryEntries;
    @Autowired FollowerRepository followers;
    @Autowired CommentRepository comments;
    @Autowired LikeRepository likes;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired RequestThrottler requestThrottler;
    @Autowired UserListDetailViewService detailViewService;

    @MockitoBean TmdbClient tmdbClient;
    @MockitoBean ContentScheduleReader scheduleReader;

    @BeforeEach
    void cleanDatabase() {
        likes.deleteAll();
        comments.deleteAll();
        items.deleteAll();
        diaryEntries.deleteAll();
        lists.deleteAll();
        contents.deleteAll();
        followers.deleteAll();
        refreshTokens.deleteAll();
        users.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
        when(scheduleReader.readMovie(anyString(), anyString(), anyString()))
                .thenReturn(new ContentScheduleLookup.Unavailable());
        when(scheduleReader.readSeason(anyString(), any(), anyString(), anyString()))
                .thenReturn(new ContentScheduleLookup.Unavailable());
        when(scheduleReader.readSeries(anyString(), anyString(), anyString()))
                .thenReturn(new ContentScheduleLookup.Unavailable());
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), anyString()))
                .thenReturn(new TmdbLookupResult.Unavailable<>());
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Start With The Detail Service Wired")
    void shouldStartWithTheDetailServiceWired() {
        assertThat(detailViewService).isNotNull();
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Return Visual Cards And Page Metadata")
    void shouldReturnVisualCardsAndPageMetadata() throws Exception {
        RegisteredUser owner = register("detailnormalowner");
        UserList list = persistList(users.findById(owner.id()).orElseThrow(), "Films", UserListVisibility.PUBLIC);
        persistContentItem(list, ContentType.MOVIE, 1, "movie-normal");
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbCardMetadata("A Movie", "/poster.jpg", "2024-03-04", 100)));

        mockMvc.perform(detailView(owner, list.getId()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Films"))
                .andExpect(jsonPath("$.items.content.length()").value(1))
                .andExpect(jsonPath("$.items.content[0].card.title").value("A Movie"))
                .andExpect(jsonPath("$.items.content[0].card.posterPath").value("/poster.jpg"))
                .andExpect(jsonPath("$.items.page").value(1))
                .andExpect(jsonPath("$.items.size").value(1))
                .andExpect(jsonPath("$.items.totalElements").value(1))
                .andExpect(jsonPath("$.items.totalPages").value(1))
                .andExpect(jsonPath("$.items.hasNext").value(false));
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Cap Episode Pages At 24 And Include Episode Metadata")
    void shouldCapEpisodePagesAt24AndIncludeEpisodeMetadata() throws Exception {
        RegisteredUser owner = register("detailepisodeowner");
        UserList list = persistList(users.findById(owner.id()).orElseThrow(), "Episodes", UserListVisibility.PUBLIC);
        for (int position = 1; position <= 25; position++) {
            persistContentItem(list, ContentType.EPISODE, position, "series-episode-" + position);
        }
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbCardMetadata("Episode title", "/still.jpg", "2025-01-02", 42)));

        mockMvc.perform(detailView(owner, list.getId()).param("type", "EPISODE").param("size", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content.length()").value(24))
                .andExpect(jsonPath("$.items.size").value(24))
                .andExpect(jsonPath("$.items.totalElements").value(25))
                .andExpect(jsonPath("$.items.hasNext").value(true))
                .andExpect(jsonPath("$.items.content[0].card.type").value("EPISODE"))
                .andExpect(jsonPath("$.items.content[0].card.title").value("Episode title"))
                .andExpect(jsonPath("$.items.content[0].card.posterPath").value("/still.jpg"))
                .andExpect(jsonPath("$.items.content[0].card.releaseDate").value("2025-01-02"));
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Return At Most Five Nested Content Previews")
    void shouldReturnAtMostFiveNestedContentPreviews() throws Exception {
        RegisteredUser owner = register("detailnestedowner");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList parent = persistList(ownerEntity, "Parent", UserListVisibility.PUBLIC);
        UserList child = persistList(ownerEntity, "Child", UserListVisibility.PUBLIC);
        persistNestedItem(parent, child, 1);
        for (int position = 1; position <= 7; position++) {
            persistContentItem(child, ContentType.MOVIE, position, "nested-movie-" + position);
        }

        mockMvc.perform(detailView(owner, parent.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content.length()").value(1))
                .andExpect(jsonPath("$.items.content[0].nestedList.name").value("Child"))
                .andExpect(jsonPath("$.items.content[0].nestedList.itemsCount").value(7))
                .andExpect(jsonPath("$.items.content[0].nestedList.previewCards.length()").value(5));
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Filter Each Watch Status Before Paging")
    void shouldFilterEachWatchStatusBeforePaging() throws Exception {
        RegisteredUser viewer = register("detailstatusviewer");
        User viewerEntity = users.findById(viewer.id()).orElseThrow();
        UserList list = persistList(viewerEntity, "Status", UserListVisibility.PUBLIC);
        Content unwatched = persistContentItem(list, ContentType.MOVIE, 1, "movie-unwatched");
        Content watched = persistContentItem(list, ContentType.MOVIE, 2, "movie-watched");
        Content partial = persistContentItem(list, ContentType.SERIES, 3, "series-partial");
        Content unknown = persistContentItem(list, ContentType.SERIES, 4, "series-unknown");
        persistDiaryEntry(viewerEntity, watched);
        Content watchedEpisode = persistEpisode("series-partial", 1, 1);
        persistDiaryEntry(viewerEntity, watchedEpisode);
        when(scheduleReader.readSeries(eq("series-partial"), anyString(), anyString()))
                .thenReturn(new ContentScheduleLookup.Found(new ContentSchedule(
                        ContentScheduleKey.series("series-partial"), null, "Returning Series",
                        List.of(
                                new ContentScheduleEpisode("series-partial", 1, 1, LocalDate.now().minusDays(2)),
                                new ContentScheduleEpisode("series-partial", 1, 2, LocalDate.now().minusDays(1))),
                        true, false)));
        when(scheduleReader.readSeries(eq("series-unknown"), anyString(), anyString()))
                .thenReturn(new ContentScheduleLookup.Unavailable());

        assertStatusFilter(viewer, list, "UNWATCHED", unwatched);
        assertStatusFilter(viewer, list, "WATCHED", watched);
        assertStatusFilter(viewer, list, "PARTIALLY_WATCHED", partial);
        assertStatusFilter(viewer, list, "UNKNOWN", unknown);
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Match Existing List Visibility Rules")
    void shouldMatchExistingListVisibilityRules() throws Exception {
        RegisteredUser owner = register("detailvisibilityowner");
        RegisteredUser acceptedViewer = register("detailvisibilityaccepted");
        RegisteredUser otherViewer = register("detailvisibilityother");
        User ownerEntity = users.findById(owner.id()).orElseThrow();
        UserList publicList = persistList(ownerEntity, "Public", UserListVisibility.PUBLIC);
        UserList followerList = persistList(ownerEntity, "Followers", UserListVisibility.FOLLOWERS);
        UserList privateList = persistList(ownerEntity, "Private", UserListVisibility.PRIVATE);
        RegisteredUser pendingViewer = register("detailvisibilitypending");
        persistFollow(acceptedViewer.id(), owner.id(), FollowStatus.ACCEPTED);
        persistFollow(pendingViewer.id(), owner.id(), FollowStatus.PENDING);

        mockMvc.perform(detailView(acceptedViewer, publicList.getId())).andExpect(status().isOk());
        mockMvc.perform(detailView(acceptedViewer, followerList.getId())).andExpect(status().isOk());
        mockMvc.perform(detailView(owner, privateList.getId())).andExpect(status().isOk());
        assertApiError(mockMvc.perform(detailView(otherViewer, followerList.getId())), 403);
        assertApiError(mockMvc.perform(detailView(pendingViewer, followerList.getId())), 403);
        assertApiError(mockMvc.perform(detailView(otherViewer, privateList.getId())), 403);
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Keep Partial And Unavailable Previews")
    void shouldKeepPartialAndUnavailablePreviews() throws Exception {
        RegisteredUser owner = register("detailpartialowner");
        UserList list = persistList(users.findById(owner.id()).orElseThrow(), "Metadata", UserListVisibility.PUBLIC);
        persistContentItem(list, ContentType.MOVIE, 1, "movie-partial");
        persistContentItem(list, ContentType.MOVIE, 2, "movie-unavailable");
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), anyString()))
                .thenAnswer(invocation -> {
                    TmdbCardMetadataKey key = invocation.getArgument(0);
                    return "movie-partial".equals(key.tmdbId())
                            ? new TmdbLookupResult.Found<>(new TmdbCardMetadata("Partial title", null, null, null))
                            : new TmdbLookupResult.Unavailable<>();
                });

        mockMvc.perform(detailView(owner, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content.length()").value(2))
                .andExpect(jsonPath("$.items.content[0].card.previewStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.items.content[1].card.previewStatus").value("UNAVAILABLE"));
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Return ApiError For Invalid Inputs And Require Authentication")
    void shouldReturnApiErrorForInvalidInputsAndRequireAuthentication() throws Exception {
        UUID unauthenticatedListId = UUID.randomUUID();
        mockMvc.perform(get("/lists/" + unauthenticatedListId + "/view"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/lists/" + unauthenticatedListId + "/view"));

        RegisteredUser viewer = register("detailinvalidviewer");
        assertApiError(mockMvc.perform(get("/lists/not-a-uuid/view").cookie(viewer.accessToken())), 400);
        UserList list = persistList(users.findById(viewer.id()).orElseThrow(), "Invalid", UserListVisibility.PUBLIC);
        for (MockHttpServletRequestBuilder invalid : List.of(
                detailView(viewer, list.getId()).param("type", "INVALID"),
                detailView(viewer, list.getId()).param("watchStatus", "INVALID"),
                detailView(viewer, list.getId()).param("sortBy", "title"),
                detailView(viewer, list.getId()).param("sortDirection", "sideways"),
                detailView(viewer, list.getId()).param("page", "0"),
                detailView(viewer, list.getId()).param("size", "0"))) {
            assertApiError(mockMvc.perform(invalid), 400);
        }
    }

    private void assertStatusFilter(RegisteredUser viewer, UserList list, String watchStatus, Content expected)
            throws Exception {
        mockMvc.perform(detailView(viewer, list.getId())
                        .param("watchStatus", watchStatus).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content.length()").value(1))
                .andExpect(jsonPath("$.items.content[0].card.contentId").value(expected.getId().toString()))
                .andExpect(jsonPath("$.items.content[0].contentState.watchStatus").value(watchStatus))
                .andExpect(jsonPath("$.items.totalElements").value(1));
    }

    private void assertApiError(org.springframework.test.web.servlet.ResultActions result, int statusCode)
            throws Exception {
        result.andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.detail").doesNotExist());
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
                .likesCount(0).createdAt(now).updatedAt(now).build());
    }

    private Content persistContentItem(UserList list, ContentType type, int position, String key) {
        LocalDateTime now = LocalDateTime.now();
        Content.ContentBuilder builder = Content.builder().type(type).createdAt(now).updatedAt(now);
        if (type == ContentType.MOVIE || type == ContentType.SERIES) {
            builder.tmdbId(key);
        } else {
            builder.seriesTmdbId(key).seasonNumber(1);
            if (type == ContentType.EPISODE) builder.episodeNumber(position);
        }
        Content content = contents.save(builder.build());
        items.save(UserListItem.builder().userList(list).content(content).position(position)
                .createdAt(now).updatedAt(now).build());
        return content;
    }

    private Content persistEpisode(String seriesTmdbId, int season, int episode) {
        LocalDateTime now = LocalDateTime.now();
        Content content = contents.save(Content.builder().type(ContentType.EPISODE).seriesTmdbId(seriesTmdbId)
                .seasonNumber(season).episodeNumber(episode).createdAt(now).updatedAt(now).build());
        return content;
    }

    private void persistDiaryEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        diaryEntries.save(DiaryEntry.builder().user(user).content(content).watchNumber(1)
                .createdAt(now).updatedAt(now).build());
    }

    private void persistNestedItem(UserList parent, UserList child, int position) {
        LocalDateTime now = LocalDateTime.now();
        items.save(UserListItem.builder().userList(parent).childList(child).position(position)
                .createdAt(now).updatedAt(now).build());
    }

    private void persistFollow(UUID followerId, UUID followedId, FollowStatus status) {
        followers.save(Follower.builder().follower(users.findById(followerId).orElseThrow())
                .followed(users.findById(followedId).orElseThrow()).status(status)
                .createdAt(LocalDateTime.now()).build());
    }

    private MockHttpServletRequestBuilder detailView(RegisteredUser viewer, UUID listId) {
        return get("/lists/{listId}/view", listId).cookie(viewer.accessToken());
    }
}
