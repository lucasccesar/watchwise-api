package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentCardStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardViewerStateDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.dto.WatchStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailViewResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPage;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageMode;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPageRequest;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListNestedDetailDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemPageReader;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserListDetailViewServiceImplTest {

    private static final UUID VIEWER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID LIST_ID = UUID.randomUUID();

    @Mock private UserListItemPageReader pageReader;
    @Mock private UserListItemRepository userListItemRepository;
    @Mock private UserListItemService userListItemService;
    @Mock private CommentRepository commentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ContentCardAssembler contentCardAssembler;
    @InjectMocks private UserListDetailViewServiceImpl service;

    private User owner;
    private User viewer;
    private UserList userList;

    @BeforeEach
    void setUp() {
        owner = user(OWNER_ID, "owner");
        viewer = user(VIEWER_ID, "viewer");
        userList = list(LIST_ID, owner, "Main list");
    }

    @Test
    @DisplayName("[getView] Should Assemble Distinct Coordinates Once And Preserve Final Page Order - When A Page Repeats Content Coordinates")
    void shouldAssembleDistinctCoordinatesOnceAndPreserveFinalPageOrderWhenAPageRepeatsContentCoordinates() {
        ContentRefDTO movie = contentRef("11", ContentType.MOVIE);
        ContentCardDTO movieCard = card(movie, ContentPreviewStatus.PARTIAL);
        ContentRefDTO series = contentRef("22", ContentType.SERIES);
        ContentCardDTO seriesCard = card(series, ContentPreviewStatus.AVAILABLE);
        UserListItemResponseDTO first = item(movie, 4);
        UserListItemResponseDTO duplicate = item(movie, 8);
        UserListItemResponseDTO third = item(series, 9);
        givenPage(List.of(first, duplicate, third));
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(coordinate(movie), movieCard, coordinate(series), seriesCard));

        UserListDetailViewResponseDTO result = service.getView(request(UserListItemPageMode.VISUAL));

        assertEquals(List.of(first.id(), duplicate.id(), third.id()),
                result.items().content().stream().map(item -> item.id()).toList());
        assertEquals(List.of(movieCard, movieCard, seriesCard),
                result.items().content().stream().map(item -> item.card()).toList());
        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<ContentCardContext> context = ArgumentCaptor.forClass(ContentCardContext.class);
        ArgumentCaptor<Set<ContentCardFieldSet>> fields = ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(specs.capture(), context.capture(), fields.capture());
        assertEquals(List.of(coordinate(movie), coordinate(series)),
                specs.getValue().stream().map(ContentCardSpec::coordinate).toList());
        assertEquals(VIEWER_ID, context.getValue().viewerId());
        assertEquals(OWNER_ID, context.getValue().posterUserId());
        assertEquals(Set.of(ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.STATS,
                ContentCardFieldSet.VIEWER_STATE, ContentCardFieldSet.SOCIAL_METADATA), fields.getValue());
        assertEquals(WatchStatus.WATCHED, movieCard.viewerState().watchStatus());
    }

    @Test
    @DisplayName("[getView] Should Preserve Partial And Unavailable Cards And Item State - When Card Assembly Returns Incomplete Previews")
    void shouldPreservePartialAndUnavailableCardsAndItemStateWhenCardAssemblyReturnsIncompletePreviews() {
        ContentRefDTO partialRef = contentRef("31", ContentType.MOVIE);
        ContentRefDTO unavailableRef = contentRef("32", ContentType.MOVIE);
        ContentCardDTO partial = card(partialRef, ContentPreviewStatus.PARTIAL);
        ContentCardDTO unavailable = card(unavailableRef, ContentPreviewStatus.UNAVAILABLE);
        ContentStateDTO state = new ContentStateDTO(WatchStatus.PARTIALLY_WATCHED, null, null, 2, 5);
        UserListItemResponseDTO first = new UserListItemResponseDTO(UUID.randomUUID(), partialRef, null, 3,
                "first", LocalDateTime.of(2026, 1, 2, 3, 4), LocalDateTime.of(2026, 1, 3, 4, 5),
                "custom-one", state, 8.5, 7.25, 9.0);
        UserListItemResponseDTO second = new UserListItemResponseDTO(UUID.randomUUID(), unavailableRef, null, 6,
                "second", LocalDateTime.of(2026, 2, 2, 3, 4), LocalDateTime.of(2026, 2, 3, 4, 5),
                "custom-two", null, null, 6.5, null);
        givenPage(List.of(first, second));
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(coordinate(partialRef), partial, coordinate(unavailableRef), unavailable));

        UserListDetailViewResponseDTO result = service.getView(request(UserListItemPageMode.VISUAL));

        assertEquals(List.of(partial, unavailable), result.items().content().stream()
                .map(item -> item.card()).toList());
        assertEquals(List.of(3, 6), result.items().content().stream().map(item -> item.position()).toList());
        assertEquals(List.of("first", "second"), result.items().content().stream().map(item -> item.description()).toList());
        assertEquals(List.of("custom-one", "custom-two"), result.items().content().stream()
                .map(item -> item.customPosterUrl()).toList());
        assertEquals(List.of(first.createdAt(), second.createdAt()), result.items().content().stream()
                .map(item -> item.createdAt()).toList());
        assertEquals(List.of(first.updatedAt(), second.updatedAt()), result.items().content().stream()
                .map(item -> item.updatedAt()).toList());
        assertEquals(state, result.items().content().getFirst().contentState());
        assertEquals(8.5, result.items().content().getFirst().episodeAverageRating());
        assertEquals(7.25, result.items().content().getFirst().globalEpisodeAverageRating());
        assertEquals(9.0, result.items().content().getFirst().contentAverageRating());
        assertEquals(6.5, result.items().content().get(1).globalEpisodeAverageRating());
        assertEquals(2, result.items().content().size());
    }

    @Test
    @DisplayName("[getView] Should Cap Nested Preview Cards And Include Them In The Single Assembly - When A Nested List Has More Than Five Content Items")
    void shouldCapNestedPreviewCardsAndIncludeThemInTheSingleAssemblyWhenANestedListHasMoreThanFiveContentItems() {
        UUID childId = UUID.randomUUID();
        UserList child = list(childId, user(UUID.randomUUID(), "child-owner"), "Child list");
        UserListPreviewDTO childPreview = new UserListPreviewDTO(childId,
                new UserPreviewDTO(child.getUser().getId(), "child-owner", "Child Owner", "avatar", true),
                "Child list", UserListVisibility.PUBLIC);
        UserListItemResponseDTO nestedItem = new UserListItemResponseDTO(UUID.randomUUID(), null, childPreview, 1,
                "nested", LocalDateTime.now(), LocalDateTime.now());
        givenPage(List.of(nestedItem));
        when(userListItemRepository.findVisibleChildListsByIdIn(List.of(childId), VIEWER_ID)).thenReturn(List.of(child));
        List<UserListItem> children = List.of(contentItem(child, "41", 1), contentItem(child, "42", 2),
                contentItem(child, "43", 3), contentItem(child, "44", 4), contentItem(child, "45", 5),
                contentItem(child, "46", 6));
        when(userListItemRepository.findContentItemsByUserListIdInOrderByPosition(List.of(childId)))
                .thenReturn(children);
        when(userListItemService.getItemsCountByListIds(List.of(childId))).thenReturn(Map.of(childId, 6L));
        when(userListItemService.getItemScopeByListIds(List.of(childId)))
                .thenReturn(Map.of(childId, UserListItemScope.MOVIE_OR_SERIES));
        when(commentRepository.countByListIdIn(List.of(childId))).thenReturn(List.of());
        when(userRepository.findById(VIEWER_ID)).thenReturn(Optional.of(viewer));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenAnswer(invocation -> {
                    Collection<ContentCardSpec> specs = invocation.getArgument(0);
                    Map<ContentCoordinate, ContentCardDTO> cards = new LinkedHashMap<>();
                    specs.forEach(spec -> cards.put(spec.coordinate(), card(spec.coordinate(), ContentPreviewStatus.AVAILABLE)));
                    return cards;
                });

        UserListDetailViewResponseDTO result = service.getView(request(UserListItemPageMode.VISUAL));

        UserListNestedDetailDTO nested = result.items().content().getFirst().nestedList();
        assertEquals(5, nested.previewCards().size());
        assertEquals(List.of("41", "42", "43", "44", "45"), nested.previewCards().stream()
                .map(ContentCardDTO::tmdbId).toList());
        ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), anySet());
        assertEquals(5, specs.getValue().size());
        verify(userListItemRepository).findContentItemsByUserListIdInOrderByPosition(List.of(childId));
    }

    @Test
    @DisplayName("[getView] Should Reject Non Visual Requests Before Reading The Page - When Request Mode Is Raw")
    void shouldRejectNonVisualRequestsBeforeReadingThePageWhenRequestModeIsRaw() {
        assertThrows(BadRequestException.class, () -> service.getView(request(UserListItemPageMode.RAW)));
        verifyNoInteractions(pageReader, contentCardAssembler, userListItemRepository);
    }

    @Test
    @DisplayName("[getView] Should Reject A Nested List That Became Invisible Before Metadata Is Loaded - When Visibility Changes During Assembly")
    void shouldRejectANestedListThatBecameInvisibleBeforeMetadataIsLoadedWhenVisibilityChangesDuringAssembly() {
        UUID childId = UUID.randomUUID();
        UserListPreviewDTO childPreview = new UserListPreviewDTO(childId,
                new UserPreviewDTO(UUID.randomUUID(), "private", "Private", "avatar", false),
                "Private list", UserListVisibility.FOLLOWERS);
        givenPage(List.of(new UserListItemResponseDTO(UUID.randomUUID(), null, childPreview, 1,
                "nested", LocalDateTime.now(), LocalDateTime.now())));
        when(userListItemRepository.findVisibleChildListsByIdIn(List.of(childId), VIEWER_ID)).thenReturn(List.of());

        assertThrows(ForbiddenException.class, () -> service.getView(request(UserListItemPageMode.VISUAL)));
        verify(contentCardAssembler, never()).assemble(anyCollection(), any(ContentCardContext.class), anySet());
        verify(userListItemRepository, never()).findContentItemsByUserListIdInOrderByPosition(anyCollection());
    }

    private void givenPage(List<UserListItemResponseDTO> items) {
        when(pageReader.read(any(UserListItemPageRequest.class))).thenReturn(new UserListItemPage(
                userList, items, 25.0, 4, true, 1, 10, items.size(), 1, false, items.size(), 7, 900, 2,
                UserListItemScope.MOVIE_OR_SERIES));
    }

    private UserListItemPageRequest request(UserListItemPageMode mode) {
        return new UserListItemPageRequest(VIEWER_ID, LIST_ID, null, null, null, null, null, 1, 10, mode);
    }

    private static ContentRefDTO contentRef(String tmdbId, ContentType type) {
        return new ContentRefDTO(UUID.randomUUID(), tmdbId, type, null, null, null, null, null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), 120, List.of("Drama"));
    }

    private static ContentCardDTO card(ContentRefDTO ref, ContentPreviewStatus status) {
        return new ContentCardDTO(ref.id(), ref.type(), ref.tmdbId(), ref.seriesTmdbId(), ref.seasonNumber(),
                ref.episodeNumber(), "Title " + ref.tmdbId(), null, null, null, null, ref.runtimeMinutes(),
                null, null, null, ref.genres(), new ContentCardStatsDTO(7.5, 3L, 2L, 1L),
                new ContentCardViewerStateDTO(WatchStatus.WATCHED, 9, null, 1, false, 2,
                        false, false, null, null), status);
    }

    private static ContentCardDTO card(ContentCoordinate coordinate, ContentPreviewStatus status) {
        return new ContentCardDTO(null, coordinate.type(), coordinate.tmdbId(), coordinate.seriesTmdbId(),
                coordinate.seasonNumber(), coordinate.episodeNumber(), "Child " + coordinate.tmdbId(), null,
                null, null, null, 90, null, null, null, List.of(), null, null, status);
    }

    private static ContentCoordinate coordinate(ContentRefDTO ref) {
        return new ContentCoordinate(ref.type(), ref.tmdbId(), ref.seriesTmdbId(), ref.seasonNumber(), ref.episodeNumber());
    }

    private static UserListItemResponseDTO item(ContentRefDTO ref, int position) {
        return new UserListItemResponseDTO(UUID.randomUUID(), ref, null, position, "description",
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 3, 2, 0, 0));
    }

    private static User user(UUID id, String username) {
        return User.builder().id(id).username(username).name(username).email(username + "@example.test")
                .password("hashed").build();
    }

    private static UserList list(UUID id, User owner, String name) {
        return UserList.builder().id(id).user(owner).name(name).description("Description")
                .visibility(UserListVisibility.PUBLIC).createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 2, 0, 0)).likesCount(3).rank(1).build();
    }

    private static UserListItem contentItem(UserList parent, String tmdbId, int position) {
        return UserListItem.builder().id(UUID.randomUUID()).userList(parent)
                .content(com.watchwise.watchwise_api.content.entity.Content.builder().id(UUID.randomUUID())
                        .tmdbId(tmdbId).type(ContentType.MOVIE).runtimeMinutes(90).genres(List.of("Drama")).build())
                .position(position).build();
    }
}
