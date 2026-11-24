package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise_test.WatchwiseIntegrationTestApplication;
import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadata;
import com.watchwise.watchwise_api.common.tmdb.TmdbCardMetadataKey;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = WatchwiseIntegrationTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class UserListDetailViewControllerIntegrationTest {

    private static final String DATABASE = "ww_list_editor_7k4";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName(DATABASE);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String host = postgres.getHost();
        int port = postgres.getMappedPort(5432);
        String url = postgres.getJdbcUrl();
        if (!List.of("localhost", "127.0.0.1").contains(host) || port == 15432 || port == 15433
                || !url.split("[?]", 2)[0].equals("jdbc:postgresql://" + host + ":" + port + "/" + DATABASE)) {
            throw new IllegalStateException("Unexpected list editor test datasource");
        }
        try (var connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database(), current_setting('server_version_num')")) {
            if (!result.next() || !DATABASE.equals(result.getString(1)) || result.getInt(2) / 10000 != 16) {
                throw new IllegalStateException("Unexpected list editor test database identity");
            }
            System.out.println("Verified isolated PostgreSQL datasource before Spring: " + url);
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to verify isolated list editor test datasource", error);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> false);
        registry.add("app.tmdb.base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender emailConfirmationSender;
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
    @DisplayName("[getUserListDetailView] Should Start With The Detail Service Wired - When Application Context Loads")
    void shouldStartWithTheDetailServiceWiredWhenApplicationContextLoads() {
        assertThat(detailViewService).isNotNull();
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Return Visual Cards And Page Metadata - When A Public List Has Content Items")
    void shouldReturnVisualCardsAndPageMetadataWhenAPublicListHasContentItems() throws Exception {
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
    @DisplayName("[getUserListDetailView] Should Cap Episode Pages At 24 And Include Episode Metadata - When Requested Size Exceeds The Episode Limit")
    void shouldCapEpisodePagesAt24AndIncludeEpisodeMetadataWhenRequestedSizeExceedsTheEpisodeLimit() throws Exception {
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
    @DisplayName("[getUserListDetailView] Should Return At Most Five Nested Content Previews - When A List Contains A Visible Child")
    void shouldReturnAtMostFiveNestedContentPreviewsWhenAListContainsAVisibleChild() throws Exception {
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
    @DisplayName("[getUserListDetailView] Should Omit Inaccessible Children Before Pagination And Metadata Loading - When A Visible Parent Has Children From Another Owner")
    void shouldOmitInaccessibleChildrenBeforePaginationAndMetadataLoadingWhenAVisibleParentHasChildrenFromAnotherOwner()
            throws Exception {
        RegisteredUser viewer = register("detailchildviewer");
        RegisteredUser childOwner = register("detailchildowner");
        UserList parent = persistList(users.findById(viewer.id()).orElseThrow(), "Parent", UserListVisibility.PUBLIC);
        UserList inaccessibleChild = persistList(
                users.findById(childOwner.id()).orElseThrow(), "Private child", UserListVisibility.PRIVATE);
        UserList visibleChild = persistList(
                users.findById(childOwner.id()).orElseThrow(), "Public child", UserListVisibility.PUBLIC);
        persistNestedItem(parent, inaccessibleChild, 1);
        persistNestedItem(parent, visibleChild, 2);
        persistContentItem(inaccessibleChild, ContentType.MOVIE, 1, "hidden-child-movie");
        persistContentItem(visibleChild, ContentType.MOVIE, 1, "visible-child-movie");
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), eq("visible-child-movie")))
                .thenReturn(new TmdbLookupResult.Found<>(
                        new TmdbCardMetadata("Visible child movie", "/visible.jpg", "2024-01-01", 90)));

        mockMvc.perform(detailView(viewer, parent.getId()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content.length()").value(1))
                .andExpect(jsonPath("$.items.totalElements").value(1))
                .andExpect(jsonPath("$.items.content[0].nestedList.name").value("Public child"))
                .andExpect(jsonPath("$.items.content[0].nestedList.previewCards[0].tmdbId")
                        .value("visible-child-movie"));

        verify(tmdbClient, never()).getCardMetadata(any(TmdbCardMetadataKey.class), eq("hidden-child-movie"));
    }

    @Test
    @DisplayName("[getUserListDetailView] Should Filter Each Watch Status Before Paging - When A Status Filter Is Requested")
    void shouldFilterEachWatchStatusBeforePagingWhenAStatusFilterIsRequested() throws Exception {
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
    @DisplayName("[getUserListDetailView] Should Match Existing List Visibility Rules - When Viewer Visibility Varies")
    void shouldMatchExistingListVisibilityRulesWhenViewerVisibilityVaries() throws Exception {
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
    @DisplayName("[getUserListDetailView] Should Keep Partial And Unavailable Previews - When Metadata Is Incomplete")
    void shouldKeepPartialAndUnavailablePreviewsWhenMetadataIsIncomplete() throws Exception {
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
    @DisplayName("[getUserListDetailView] Should Return ApiError For Invalid Inputs And Require Authentication - When Requests Are Invalid Or Unauthenticated")
    void shouldReturnApiErrorForInvalidInputsAndRequireAuthenticationWhenRequestsAreInvalidOrUnauthenticated()
            throws Exception {
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

    @Test
    void editorFetchesTheCompleteOrderedCollectionAndSignalsOverflowWithoutPartialItems() throws Exception {
        RegisteredUser owner = register("editorvolumes");
        UserList list = persistList(users.findById(owner.id()).orElseThrow(), "Editor volumes", UserListVisibility.PRIVATE);
        when(tmdbClient.getCardMetadata(any(TmdbCardMetadataKey.class), anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbCardMetadata("Title", "/poster.jpg", "2024-01-01", 95)));
        mockMvc.perform(editor(owner, list.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY")).andExpect(jsonPath("$.items").isEmpty());
        for (int position = 100; position > 0; position--) {
            persistContentItem(list, position % 2 == 0 ? ContentType.SERIES : ContentType.MOVIE,
                    position, Integer.toString(80000 + position));
        }
        var expected = items.findByUserListIdOrderByPositionAsc(list.getId());
        mockMvc.perform(editor(owner, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.itemScope").value("MOVIE_OR_SERIES"))
                .andExpect(jsonPath("$.items[*].id").value(contains(expected.stream().map(i -> i.getId().toString()).toArray(String[]::new))))
                .andExpect(jsonPath("$.items[0].card.title").value("Title"))
                .andExpect(jsonPath("$.items[0].card.releaseYear").value(2024))
                .andExpect(jsonPath("$.items[0].card.posterPath").value("/poster.jpg"))
                .andExpect(jsonPath("$.items[0].card.viewerState").doesNotExist())
                .andExpect(jsonPath("$.items[1].card.type").value("SERIES"))
                .andExpect(jsonPath("$.items[99].position").value(100));
        persistContentItem(list, ContentType.SERIES, 101, "80101");
        mockMvc.perform(editor(owner, list.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LEGACY"))
                .andExpect(jsonPath("$.items").doesNotExist());
        mockMvc.perform(detailView(owner, list.getId()).param("page", "4").param("size", "30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.totalElements").value(101))
                .andExpect(jsonPath("$.items.content.length()").value(11));
    }

    @Test
    void editorDoesNotExposeItemsOutsideOwnerAccessAndDefersNestedLists() throws Exception {
        RegisteredUser actor = register("editornonowner");
        RegisteredUser owner = register("editorprivacy");
        User user = users.findById(owner.id()).orElseThrow();
        var publicList = persistList(user, "Visible list", UserListVisibility.PUBLIC);
        var privateList = persistList(user, "Private list", UserListVisibility.PRIVATE);
        persistNestedItem(privateList, publicList, 1);

        mockMvc.perform(get("/lists/{listId}/editor", publicList.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(editor(actor, publicList.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READ_ONLY"))
                .andExpect(jsonPath("$.name").doesNotExist()).andExpect(jsonPath("$.items").doesNotExist());
        mockMvc.perform(editor(actor, privateList.getId())).andExpect(status().isForbidden());
        mockMvc.perform(editor(owner, UUID.randomUUID())).andExpect(status().isNotFound());
        mockMvc.perform(editor(owner, privateList.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LEGACY"))
                .andExpect(jsonPath("$.items").doesNotExist());
        mockMvc.perform(detailView(owner, privateList.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[0].nestedList.id").value(publicList.getId().toString()));
    }

    @Test
    void editorReflectsChangesPersistedByTheExistingWriteRoutes() throws Exception {
        RegisteredUser owner = register("editorwrites");
        UserList list = persistList(users.findById(owner.id()).orElseThrow(), "Before", UserListVisibility.PRIVATE);
        persistContentItem(list, ContentType.MOVIE, 1, "83001");
        persistContentItem(list, ContentType.SERIES, 2, "83002");
        persistContentItem(list, ContentType.MOVIE, 3, "83003");
        var initial = items.findByUserListIdOrderByPositionAsc(list.getId());
        when(tmdbClient.getMovieFullDetails(eq("83001"), anyString()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbMovieFullDetails("83001", "Re-added", null)));
        mockMvc.perform(write(owner, patch("/lists/{listId}", list.getId()))
                        .content("{\"name\":\"After\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(owner, delete("/lists/{listId}/items/{id}", list.getId(), initial.getFirst().getId())))
                .andExpect(status().isNoContent());
        mockMvc.perform(write(owner, patch("/lists/{listId}/items/{id}", list.getId(), initial.getLast().getId()))
                        .content("{\"position\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(owner, post("/lists/{listId}/items", list.getId()))
                        .content("{\"content\":{\"type\":\"MOVIE\",\"tmdbId\":\"83001\"}}"))
                .andExpect(status().isCreated());

        mockMvc.perform(editor(owner, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("After"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.items[*].position").value(contains(1, 2, 3)))
                .andExpect(jsonPath("$.items[*].card.tmdbId").value(contains("83003", "83002", "83001")));
        mockMvc.perform(detailView(owner, list.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.content[*].card.tmdbId").value(contains("83003", "83002", "83001")));
    }

    private MockHttpServletRequestBuilder editor(RegisteredUser viewer, UUID listId) {
        return get("/lists/{listId}/editor", listId).cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder write(RegisteredUser viewer, MockHttpServletRequestBuilder request) {
        return request.cookie(viewer.accessToken(), viewer.csrfToken())
                .header("X-XSRF-TOKEN", viewer.csrfToken().getValue()).contentType(MediaType.APPLICATION_JSON);
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
        var user = com.watchwise.watchwise_api.user.service.TestRegistrationHelper.register(
                mockMvc, users, emailConfirmationSender,
                username, username, username + "@email.com", true);
        return new RegisteredUser(user.id(), user.accessToken(), user.csrfToken());
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
