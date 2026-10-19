package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.*;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListViewRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserListViewServiceImplTest {
    @Mock UserListViewRepository repository;
    @Mock UserListItemRepository itemRepository;
    @Mock UserListItemService itemService;
    @Mock CommentRepository comments;
    @Mock LikeService likes;
    @Mock UserRepository users;
    @Mock FollowerRepository followers;
    @Mock ContentCardAssembler contentCards;
    UserListViewServiceImpl service;
    UUID viewer = UUID.randomUUID();

    @BeforeEach void setup() {
        service = new UserListViewServiceImpl(repository, itemRepository, itemService, comments,
                likes, users, followers, contentCards, new PageRequestFactory());
    }

    @Test
    @DisplayName("[getView] Should Validate Mode Source Owner Sort Direction And Page Size - When Request Is Invalid")
    void shouldValidateModeSourceOwnerSortDirectionAndPageSizeWhenRequestIsInvalid() {
        assertBad(request(null, null, null, null, null, null, 1, 10));
        assertBad(request(UserListViewQueryMode.USER, UserListViewSource.OWNED, viewer, null, null, null, 1, 10));
        assertBad(request(UserListViewQueryMode.USER, null, null, null, null, null, 1, 10));
        assertBad(request(UserListViewQueryMode.DISCOVER, null, viewer, null, null, null, 1, 10));
        assertBad(request(UserListViewQueryMode.MY, UserListViewSource.LIKED, null, null, "rank", null, 1, 10));
        assertBad(request(UserListViewQueryMode.DISCOVER, null, null, null, "updatedAt", null, 1, 10));
        assertBad(request(UserListViewQueryMode.MY, UserListViewSource.OWNED, null, null, null, "up", 1, 10));
        assertBad(request(UserListViewQueryMode.MY, UserListViewSource.OWNED, null, null, null, null, -1, 10));
        assertBad(request(UserListViewQueryMode.MY, UserListViewSource.OWNED, null, null, null, null, 1, 9));
        assertBad(request(UserListViewQueryMode.DISCOVER, null, null, "x".repeat(101), null, null, 1, 10));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("[getView] Should Default An Omitted My Source To All - When Source Is Omitted For My Mode")
    void shouldDefaultOmittedMySourceToAllWhenQueryingRepository() {
        when(repository.findPage(any())).thenAnswer(invocation -> {
            UserListViewQuery query = invocation.getArgument(0);
            return new PageImpl<UserList>(List.of(), query.pageable(), 21);
        });

        var result = service.getView(request(UserListViewQueryMode.MY, null, null,
                null, null, null, 2, 10));

        ArgumentCaptor<UserListViewQuery> captor = ArgumentCaptor.forClass(UserListViewQuery.class);
        verify(repository).findPage(captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(UserListViewSource.ALL);
        assertThat(captor.getValue().pageable().getPageNumber()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(21);
    }

    @Test
    @DisplayName("[getView] Should Translate Pagination And Escape Search - When Search And Pagination Are Provided")
    void shouldTranslatePaginationAndEscapeSearchWhenSearchAndPaginationAreProvided() {
        when(repository.findPage(any())).thenAnswer(inv -> {
            UserListViewQuery query = inv.getArgument(0);
            return new PageImpl<UserList>(List.of(), query.pageable(), 31);
        });
        var result = service.getView(request(UserListViewQueryMode.DISCOVER, null, null,
                "  100%_\\  ", "likesCount", "desc", 2, 99));
        ArgumentCaptor<UserListViewQuery> captor = ArgumentCaptor.forClass(UserListViewQuery.class);
        verify(repository).findPage(captor.capture());
        UserListViewQuery query = captor.getValue();
        assertThat(query.query()).isEqualTo("100\\%\\_\\\\");
        assertThat(query.rawQuery()).isEqualTo("100%_\\");
        assertThat(query.sortBy()).isEqualTo(UserListViewSort.LIKES_COUNT);
        assertThat(query.direction()).isEqualTo(Sort.Direction.DESC);
        assertThat(query.pageable().getPageNumber()).isEqualTo(1);
        assertThat(query.pageable().getPageSize()).isEqualTo(10);
        assertThat(result.getTotalElements()).isEqualTo(31);
        assertThat(result.getNumber()).isEqualTo(1);
        verifyNoInteractions(itemService, comments, likes, contentCards);
    }

    @Test
    @DisplayName("[getView] Should Preserve Visual Search Page Size Up To Twenty - When Search Uses The Default Size")
    void shouldPreserveVisualSearchPageSizeUpToTwentyWhenSearchUsesTheDefaultSize() {
        when(repository.findPage(any())).thenAnswer(inv -> {
            UserListViewQuery query = inv.getArgument(0);
            return new PageImpl<UserList>(List.of(), query.pageable(), 0);
        });

        var result = service.getView(new UserListViewRequest(
                viewer, null, UserListViewQueryMode.SEARCH, null, "night", null, null,
                null, null, 1, 18));

        ArgumentCaptor<UserListViewQuery> captor = ArgumentCaptor.forClass(UserListViewQuery.class);
        verify(repository).findPage(captor.capture());
        assertThat(captor.getValue().pageable().getPageSize()).isEqualTo(18);
        assertThat(result.getSize()).isEqualTo(18);
    }

    @Test
    @DisplayName("[getView] Should Omit Blank Discovery Query And Normalize Zero Page - When Building The Repository Query")
    void shouldOmitBlankDiscoveryQueryAndNormalizeZeroPageWhenBuildingRepositoryQuery() {
        when(repository.findPage(any())).thenAnswer(inv -> {
            UserListViewQuery query = inv.getArgument(0);
            return new PageImpl<UserList>(List.of(), query.pageable(), 0);
        });
        service.getView(request(UserListViewQueryMode.DISCOVER, null, null, "   ",
                null, "desc", 0, null));
        ArgumentCaptor<UserListViewQuery> captor = ArgumentCaptor.forClass(UserListViewQuery.class);
        verify(repository).findPage(captor.capture());
        assertThat(captor.getValue().query()).isNull();
        assertThat(captor.getValue().pageable().getPageNumber()).isZero();
        assertThat(captor.getValue().pageable().getPageSize()).isEqualTo(10);
        assertThat(captor.getValue().direction()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("[getView] Should Block A Private Profile - When Viewer Cannot Access The Owner")
    void shouldBlockPrivateProfileWhenViewerCannotAccessTheOwner() {
        UUID ownerId = UUID.randomUUID();
        when(users.findById(ownerId)).thenReturn(Optional.of(com.watchwise.watchwise_api.user.entity.User.builder()
                .id(ownerId).isProfilePublic(false).build()));
        assertThatThrownBy(() -> service.getView(request(UserListViewQueryMode.USER, null, ownerId,
                null, null, null, 1, 10))).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("[getView] Should Batch Overview Collaborators And Preserve Order - When Assembling A Page")
    void shouldBatchOverviewCollaboratorsAndKeepOrderWhenAssemblingPage() {
        UserList list = UserList.builder().id(UUID.randomUUID()).user(com.watchwise.watchwise_api.user.entity.User.builder()
                .id(viewer).username("viewer").name("Viewer").isProfilePublic(true).preferredLanguage("pt-BR")
                .preferredRegion("BR").build()).name("one").visibility(UserListVisibility.PUBLIC)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).likesCount(3).build();
        when(repository.findPage(any())).thenReturn(new PageImpl<>(List.of(list), PageRequest.of(0, 10), 1));
        when(itemService.getPreviewItemsByListIds(any())).thenReturn(Map.of());
        when(itemService.countNestedListsByListIds(any())).thenReturn(Map.of());
        when(itemService.getItemsCountByListIds(any())).thenReturn(Map.of());
        when(itemService.getScopeSummariesByListIds(any(), any())).thenReturn(Map.of());
        when(likes.getLikedListIds(eq(viewer), any())).thenReturn(Set.of());
        when(users.findAllById(any())).thenReturn(List.of(list.getUser()));
        when(itemRepository.findNestedPreviewsByParentIdIn(any(), eq(viewer))).thenReturn(List.of());
        var result = service.getView(request(UserListViewQueryMode.MY, UserListViewSource.OWNED,
                null, null, null, null, 1, 10));
        assertThat(result.getContent()).extracting(UserListCardDTO::id).containsExactly(list.getId());
        assertThat(result.getContent().get(0).ownedByMe()).isTrue();
        verify(itemService, never()).getWatchedPercentagesByListIds(any(), any());
        verify(itemService, never()).getTotalRuntimeMinutesByListIds(any());
        verify(comments).countVisibleListComments(eq(viewer), eq(List.of(list.getId())));
    }

    @Test
    @DisplayName("[getView] Should Reuse Basic Metadata For Repeated Coordinates - When Coordinates Repeat Across Lists")
    void shouldReuseBasicMetadataForRepeatedCoordinatesWhenCoordinatesRepeatAcrossLists() {
        var owner = com.watchwise.watchwise_api.user.entity.User.builder().id(viewer).username("v")
                .name("Viewer").isProfilePublic(true).preferredLanguage("pt-BR").preferredRegion("BR").build();
        UserList first = UserList.builder().id(UUID.randomUUID()).user(owner).name("first")
                .visibility(UserListVisibility.PUBLIC).likesCount(0).build();
        UserList second = UserList.builder().id(UUID.randomUUID()).user(owner).name("second")
                .visibility(UserListVisibility.PUBLIC).likesCount(0).build();
        ContentRefDTO ref = new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE,
                null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now());
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, "550", null, null, null);
        ContentCardDTO card = mock(ContentCardDTO.class);
        when(repository.findPage(any())).thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 10), 2));
        when(itemService.getPreviewItemsByListIds(any())).thenReturn(Map.of(first.getId(), List.of(ref), second.getId(), List.of(ref)));
        when(itemService.countNestedListsByListIds(any())).thenReturn(Map.of());
        when(itemService.getItemsCountByListIds(any())).thenReturn(Map.of());
        when(itemService.getScopeSummariesByListIds(any(), any())).thenReturn(Map.of());
        when(likes.getLikedListIds(eq(viewer), any())).thenReturn(Set.of());
        when(users.findAllById(any())).thenReturn(List.of(owner));
        when(itemRepository.findNestedPreviewsByParentIdIn(any(), eq(viewer))).thenReturn(List.of());
        when(contentCards.assemble(anyCollection(), any(), eq(ContentCardFieldSet.BASIC_METADATA)))
                .thenReturn(Map.of(coordinate, card));

        var result = service.getView(request(UserListViewQueryMode.MY, UserListViewSource.OWNED,
                null, null, null, null, 1, 10));
        assertThat(result.getContent()).extracting(UserListCardDTO::id).containsExactly(first.getId(), second.getId());
        assertThat(result.getContent().get(0).previewCards()).containsExactly(card);
        assertThat(result.getContent().get(1).previewCards()).containsExactly(card);
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<ContentCardSpec>> specs = ArgumentCaptor.forClass(Collection.class);
        verify(contentCards, times(1)).assemble(specs.capture(), any(), eq(ContentCardFieldSet.BASIC_METADATA));
        assertThat(specs.getValue()).extracting(ContentCardSpec::coordinate).containsExactly(coordinate);
    }

    @Test
    @DisplayName("[getView] Should Remove Private Nested Previews - When Assembling A Card")
    void shouldRemovePrivateNestedPreviewsWhenAssemblingCard() {
        var owner = com.watchwise.watchwise_api.user.entity.User.builder().id(viewer).username("v")
                .name("Viewer").isProfilePublic(true).build();
        UserList parent = UserList.builder().id(UUID.randomUUID()).user(owner).name("parent")
                .visibility(UserListVisibility.PUBLIC).likesCount(0).build();
        UUID childOwner = UUID.randomUUID();
        UserListItemRepository.NestedPreview hidden = nested(parent.getId(), UUID.randomUUID(), childOwner,
                "secret", "PRIVATE");
        UserListItemRepository.NestedPreview visible = nested(parent.getId(), UUID.randomUUID(), childOwner,
                "public", "PUBLIC");
        when(repository.findPage(any())).thenReturn(new PageImpl<>(List.of(parent), PageRequest.of(0, 10), 1));
        when(itemService.getPreviewItemsByListIds(any())).thenReturn(Map.of());
        when(itemService.countNestedListsByListIds(any())).thenReturn(Map.of());
        when(itemService.getItemsCountByListIds(any())).thenReturn(Map.of());
        when(itemService.getScopeSummariesByListIds(any(), any())).thenReturn(Map.of());
        when(likes.getLikedListIds(eq(viewer), any())).thenReturn(Set.of());
        when(users.findAllById(any())).thenReturn(List.of(owner));
        when(itemRepository.findNestedPreviewsByParentIdIn(any(), eq(viewer))).thenReturn(List.of(hidden, visible));
        var card = service.getView(request(UserListViewQueryMode.MY, UserListViewSource.OWNED,
                null, null, null, null, 1, 10)).getContent().get(0);
        assertThat(card.nestedListPreviews()).extracting(UserListPreviewDTO::id).containsExactly(visible.getChildId());
        assertThat(card.nestedListPreviews()).extracting(UserListPreviewDTO::name).doesNotContain("secret");
    }

    private UserListItemRepository.NestedPreview nested(UUID parent, UUID child, UUID owner, String name, String visibility) {
        var row = mock(UserListItemRepository.NestedPreview.class);
        if ("PUBLIC".equals(visibility)) {
            when(row.getParentId()).thenReturn(parent);
            when(row.getChildId()).thenReturn(child);
        }
        when(row.getOwnerId()).thenReturn(owner);
        when(row.getVisibility()).thenReturn(visibility);
        if ("PUBLIC".equals(visibility)) {
            when(row.getUsername()).thenReturn("child-owner");
            when(row.getOwnerName()).thenReturn("Child Owner");
            when(row.getChildName()).thenReturn(name);
        }
        return row;
    }

    private void assertBad(UserListViewRequest request) {
        assertThatThrownBy(() -> service.getView(request)).isInstanceOf(BadRequestException.class);
    }
    private UserListViewRequest request(UserListViewQueryMode mode, UserListViewSource source, UUID owner,
            String query, String sortBy, String direction, Integer page, Integer size) {
        return new UserListViewRequest(viewer, owner, mode, source, query, null, null,
                sortBy, direction, page, size);
    }
}
