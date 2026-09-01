package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListDetailedResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemScope;
import com.watchwise.watchwise_api.userlist.dto.UserListPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserListServiceImplTest {

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private UserListItemService userListItemService;

    @Mock
    private UserListMapper userListMapper;

    @Mock
    private LikeService likeService;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @InjectMocks
    private UserListServiceImpl userListService;

    @Captor
    private ArgumentCaptor<UserList> listCaptor;

    @Captor
    private ArgumentCaptor<UserListItemBulkCreationDTO> itemsCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    @Captor
    private ArgumentCaptor<List<UserListVisibility>> visibilitiesCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;

    @BeforeEach
    void setUp() {
        lucasId = UUID.randomUUID();
        marinaId = UUID.randomUUID();

        lucas = buildUser(lucasId, "lucas", true);
        marina = buildUser(marinaId, "marina", true);

        lenient().when(likeService.getLikedListIds(any(), any())).thenReturn(Set.of());
    }

    // ---------- getUserLists ----------

    @Test
    @DisplayName("[getUserLists] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwner() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListResponseDTO dto = buildResponseDto(list);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.getWatchedPercentagesByListIds(List.of(list.getId()), lucasId)).thenReturn(Map.of());
        when(userListMapper.userListToResponseDto(list, List.of(), 0L, 0.0, false, 0L, 0L, 0L, null)).thenReturn(dto);

        Page<UserListResponseDTO> result = userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getUserLists] Should Populate Preview Items And Nested Lists Count - When Mapping Each List")
    void shouldPopulatePreviewItemsAndNestedListsCountWhenMappingEachList() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        List<ContentRefDTO> previewItems = List.of(
                new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, null, null));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(list.getId()))).thenReturn(Map.of(list.getId(), previewItems));
        when(userListItemService.countNestedListsByListIds(List.of(list.getId()))).thenReturn(Map.of(list.getId(), 2L));
        when(userListItemService.getWatchedPercentagesByListIds(List.of(list.getId()), lucasId)).thenReturn(Map.of());
        when(userListMapper.userListToResponseDto(list, previewItems, 2L, 0.0, false, 0L, 0L, 0L, null)).thenReturn(buildResponseDto(list));

        userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        verify(userListMapper).userListToResponseDto(list, previewItems, 2L, 0.0, false, 0L, 0L, 0L, null);
    }

    @Test
    @DisplayName("[getUserLists] Should Populate ItemsCount, CommentsCount And TotalRuntimeMinutes - When Mapping Each List")
    void shouldPopulateItemsCountCommentsCountAndTotalRuntimeMinutesWhenMappingEachList() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.getWatchedPercentagesByListIds(List.of(list.getId()), lucasId)).thenReturn(Map.of());
        when(userListItemService.getItemsCountByListIds(List.of(list.getId()))).thenReturn(Map.of(list.getId(), 4L));
        when(userListItemService.getTotalRuntimeMinutesByListIds(List.of(list.getId()))).thenReturn(Map.of(list.getId(), 360L));
        when(commentRepository.countByListIdIn(List.of(list.getId())))
                .thenReturn(List.of(buildListCommentCount(list.getId(), 7L)));
        when(userListMapper.userListToResponseDto(eq(list), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(buildResponseDto(list));

        ArgumentCaptor<Long> itemsCountCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> commentsCountCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> totalRuntimeMinutesCaptor = ArgumentCaptor.forClass(Long.class);

        userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        verify(userListMapper).userListToResponseDto(eq(list), any(), anyLong(), anyDouble(), anyBoolean(),
                itemsCountCaptor.capture(), commentsCountCaptor.capture(), totalRuntimeMinutesCaptor.capture(), any());
        assertThat(itemsCountCaptor.getValue()).isEqualTo(4L);
        assertThat(commentsCountCaptor.getValue()).isEqualTo(7L);
        assertThat(totalRuntimeMinutesCaptor.getValue()).isEqualTo(360L);
    }

    private CommentRepository.ListCommentCount buildListCommentCount(UUID listId, long count) {
        return new CommentRepository.ListCommentCount() {
            @Override
            public UUID getListId() {
                return listId;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("[getUserLists] Should Populate Watched Percentage - Using The Viewer's Own Watch History")
    void shouldPopulateWatchedPercentageUsingTheViewersOwnWatchHistoryOnGetUserLists() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.getWatchedPercentagesByListIds(List.of(list.getId()), lucasId)).thenReturn(Map.of(list.getId(), 75.0));
        when(userListMapper.userListToResponseDto(list, List.of(), 0L, 75.0, false, 0L, 0L, 0L, null)).thenReturn(buildResponseDto(list));

        userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        verify(userListMapper).userListToResponseDto(list, List.of(), 0L, 75.0, false, 0L, 0L, 0L, null);
    }

    @Test
    @DisplayName("[getUserLists] Should Populate Watched Percentage - Using The Viewer's History, Not The Owner's, When Viewing Another User's List")
    void shouldPopulateWatchedPercentageUsingTheViewersHistoryNotTheOwnersWhenViewingAnotherUsersList() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(false);
        when(userListRepository.findByUserIdAndVisibilityIn(eq(lucasId), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.getWatchedPercentagesByListIds(List.of(list.getId()), marinaId)).thenReturn(Map.of(list.getId(), 10.0));
        when(userListMapper.userListToResponseDto(list, List.of(), 0L, 10.0, false, 0L, 0L, 0L, null)).thenReturn(buildResponseDto(list));

        userListService.getUserLists(marinaId, lucasId, 1, 10, null, null);

        verify(userListItemService).getWatchedPercentagesByListIds(List.of(list.getId()), marinaId);
        verify(userListItemService, never()).getWatchedPercentagesByListIds(List.of(list.getId()), lucasId);
    }

    @Test
    @DisplayName("[getUserLists] Should Return Empty Page - When User Has No Lists")
    void shouldReturnEmptyPageWhenUserHasNoLists() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());
        when(userListItemService.getPreviewItemsByListIds(List.of())).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of())).thenReturn(Map.of());
        when(userListItemService.getWatchedPercentagesByListIds(List.of(), lucasId)).thenReturn(Map.of());

        Page<UserListResponseDTO> result = userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        assertThat(result.getContent()).isEmpty();
        verify(userListMapper, never()).userListToResponseDto(any(), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, 10, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(userListRepository);
    }

    @Test
    @DisplayName("[getUserLists] Should Query All Lists - When Viewer Is The Owner")
    void shouldQueryAllListsWhenViewerIsTheOwner() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), any(PageRequest.class));
        verify(userListRepository, never()).findByUserIdAndVisibilityIn(any(), any(), any());
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getUserLists] Should Not Throw - When Owner's Own Profile Is Private")
    void shouldNotThrowWhenOwnersOwnProfileIsPrivate() {
        User privateLucas = buildUser(lucasId, "lucas", false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(privateLucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(lucasId, lucasId, 1, 10, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getUserLists] Should Query Only Public Visibility - When Target Profile Is Public And Viewer Does Not Follow")
    void shouldQueryOnlyPublicVisibilityWhenTargetProfileIsPublicAndViewerDoesNotFollow() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(false);
        when(userListRepository.findByUserIdAndVisibilityIn(eq(lucasId), any(), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(marinaId, lucasId, 1, 10, null, null);

        verify(userListRepository).findByUserIdAndVisibilityIn(eq(lucasId), visibilitiesCaptor.capture(), any(PageRequest.class));
        assertThat(visibilitiesCaptor.getValue()).containsExactly(UserListVisibility.PUBLIC);
        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Query Public And Followers Visibility - When Viewer Follows Target")
    void shouldQueryPublicAndFollowersVisibilityWhenViewerFollowsTarget() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(true);
        when(userListRepository.findByUserIdAndVisibilityIn(eq(lucasId), any(), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(marinaId, lucasId, 1, 10, null, null);

        verify(userListRepository).findByUserIdAndVisibilityIn(eq(lucasId), visibilitiesCaptor.capture(), any(PageRequest.class));
        assertThat(visibilitiesCaptor.getValue()).containsExactlyInAnyOrder(UserListVisibility.PUBLIC, UserListVisibility.FOLLOWERS);
    }

    @Test
    @DisplayName("[getUserLists] Should Allow Access - When Target Profile Is Private But Viewer Follows Target")
    void shouldAllowAccessWhenTargetProfileIsPrivateButViewerFollowsTarget() {
        User privateLucas = buildUser(lucasId, "lucas", false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(privateLucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(true);
        when(userListRepository.findByUserIdAndVisibilityIn(eq(lucasId), any(), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(marinaId, lucasId, 1, 10, null, null);

        verify(userListRepository).findByUserIdAndVisibilityIn(eq(lucasId), any(), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getUserLists] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Does Not Follow")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerDoesNotFollow() {
        User privateLucas = buildUser(lucasId, "lucas", false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(privateLucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> userListService.getUserLists(marinaId, lucasId, 1, 10, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(userListRepository, never()).findByUserId(any(), any());
        verify(userListRepository, never()).findByUserIdAndVisibilityIn(any(), any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, null, 10, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 0, 10, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 3, 10, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, -1, 10, null, null))
                .isInstanceOf(BadRequestException.class);

        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, null, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getUserLists] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, 1001, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, 25, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, 1000, null, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, -5, null, null))
                .isInstanceOf(BadRequestException.class);

        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, 0, null, null))
                .isInstanceOf(BadRequestException.class);

        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When SortBy Is Invalid")
    void shouldThrowBadRequestExceptionWhenSortByIsInvalid() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, 10, "unknownField", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("sortBy must be one of: rank, updatedAt, name, likesCount, itemsCount, commentsCount");

        verifyNoInteractions(userListRepository);
    }

    @Test
    @DisplayName("[getUserLists] Should Use The ItemsCount Native Query - When SortBy Is ItemsCount")
    void shouldUseTheItemsCountNativeQueryWhenSortByIsItemsCount() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserIdOrderByItemsCount(eq(lucasId), any(), eq("DESC"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userListService.getUserLists(lucasId, lucasId, 1, 10, "itemsCount", "desc");

        verify(userListRepository).findByUserIdOrderByItemsCount(eq(lucasId), any(), eq("DESC"), any(PageRequest.class));
        verifyNoInteractions(userListMapper);
    }

    @Test
    @DisplayName("[getUserLists] Should Use The CommentsCount Native Query - When SortBy Is CommentsCount")
    void shouldUseTheCommentsCountNativeQueryWhenSortByIsCommentsCount() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserIdOrderByCommentsCount(eq(lucasId), any(), eq("ASC"), any(PageRequest.class)))
                .thenReturn(Page.empty());

        userListService.getUserLists(lucasId, lucasId, 1, 10, "commentsCount", null);

        verify(userListRepository).findByUserIdOrderByCommentsCount(eq(lucasId), any(), eq("ASC"), any(PageRequest.class));
    }

    // ---------- getLikedLists ----------

    @Test
    @DisplayName("[getLikedLists] Should Return Mapped Page - When User Has Liked Lists")
    void shouldReturnMappedPageWhenUserHasLikedLists() {
        UserList list = buildList(marina, "Marina's list", null, UserListVisibility.PUBLIC);
        UserListResponseDTO dto = buildResponseDto(list);
        when(likeRepository.findLikedListsByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListItemService.getPreviewItemsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.countNestedListsByListIds(List.of(list.getId()))).thenReturn(Map.of());
        when(userListItemService.getWatchedPercentagesByListIds(List.of(list.getId()), lucasId)).thenReturn(Map.of());
        when(userListMapper.userListToResponseDto(list, List.of(), 0L, 0.0, false, 0L, 0L, 0L, null)).thenReturn(dto);

        Page<UserListResponseDTO> result = userListService.getLikedLists(lucasId, 1, 10);

        assertThat(result.getContent()).containsExactly(dto);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("[getLikedLists] Should Return Empty Page - When User Has Not Liked Any List")
    void shouldReturnEmptyPageWhenUserHasNotLikedAnyList() {
        when(likeRepository.findLikedListsByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        Page<UserListResponseDTO> result = userListService.getLikedLists(lucasId, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getLikedLists] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNullForLikedLists() {
        when(likeRepository.findLikedListsByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getLikedLists(lucasId, null, 10);

        verify(likeRepository).findLikedListsByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("[getLikedLists] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositiveForLikedLists() {
        when(likeRepository.findLikedListsByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getLikedLists(lucasId, 3, 10);

        verify(likeRepository).findLikedListsByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getLikedLists] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegativeForLikedLists() {
        assertThatThrownBy(() -> userListService.getLikedLists(lucasId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(likeRepository);
    }

    @Test
    @DisplayName("[getLikedLists] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNullForLikedLists() {
        when(likeRepository.findLikedListsByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getLikedLists(lucasId, 1, null);

        verify(likeRepository).findLikedListsByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getLikedLists] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimitForLikedLists() {
        when(likeRepository.findLikedListsByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getLikedLists(lucasId, 1, 1001);

        verify(likeRepository).findLikedListsByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getLikedLists] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegativeForLikedLists() {
        assertThatThrownBy(() -> userListService.getLikedLists(lucasId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(likeRepository);
    }

    @Test
    @DisplayName("[getLikedLists] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZeroForLikedLists() {
        assertThatThrownBy(() -> userListService.getLikedLists(lucasId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(likeRepository);
    }

    // ---------- getUserListById ----------

    @Test
    @DisplayName("[getUserListById] Should Return Detailed List With Items - When Viewer Is The Owner")
    void shouldReturnDetailedListWithItemsWhenViewerIsTheOwner() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PRIVATE);
        List<UserListItemResponseDTO> items = List.of(buildItemResponseDto(buildContentRef("100", ContentType.MOVIE)));
        UserListDetailedResponseDTO dto = buildDetailedResponseDto(list, items);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(items);
        when(userListMapper.userListToDetailedResponseDto(list, items, 0.0, false, 1L, 0L, 0L, UserListItemScope.MOVIE_OR_SERIES)).thenReturn(dto);

        UserListDetailedResponseDTO result = userListService.getUserListById(lucasId, list.getId(), null, null, null, null);

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("[getUserListById] Should Filter Items By Type")
    void shouldFilterItemsByType() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO movieItem = buildItemResponseDtoWithContent(ContentType.MOVIE, null, null, 1);
        UserListItemResponseDTO seriesItem = buildItemResponseDtoWithContent(ContentType.SERIES, null, null, 2);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(List.of(movieItem, seriesItem));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), eq(2L), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(list, invocation.getArgument(1)));

        UserListDetailedResponseDTO result = userListService.getUserListById(lucasId, list.getId(), ContentType.MOVIE, null, null, null);

        assertThat(result.items()).containsExactly(movieItem);
    }

    @Test
    @DisplayName("[getUserListById] Should Filter Items By Genre")
    void shouldFilterItemsByGenre() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO dramaItem = buildItemResponseDtoWithContent(ContentType.MOVIE, null, List.of("Drama"), 1);
        UserListItemResponseDTO comedyItem = buildItemResponseDtoWithContent(ContentType.MOVIE, null, List.of("Comedy"), 2);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(List.of(dramaItem, comedyItem));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), eq(2L), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(list, invocation.getArgument(1)));

        UserListDetailedResponseDTO result = userListService.getUserListById(lucasId, list.getId(), null, "Drama", null, null);

        assertThat(result.items()).containsExactly(dramaItem);
    }

    @Test
    @DisplayName("[getUserListById] Should Sort Items By Duration Descending")
    void shouldSortItemsByDurationDescending() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO shortItem = buildItemResponseDtoWithContent(ContentType.MOVIE, 90, null, 1);
        UserListItemResponseDTO longItem = buildItemResponseDtoWithContent(ContentType.MOVIE, 180, null, 2);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(List.of(shortItem, longItem));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), eq(2L), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(list, invocation.getArgument(1)));

        UserListDetailedResponseDTO result = userListService.getUserListById(lucasId, list.getId(), null, null, "duration", "desc");

        assertThat(result.items()).containsExactly(longItem, shortItem);
    }

    @Test
    @DisplayName("[getUserListById] Should Throw BadRequestException - When Item SortBy Is Invalid")
    void shouldThrowBadRequestExceptionWhenItemSortByIsInvalid() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> userListService.getUserListById(lucasId, list.getId(), null, null, "unknownField", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("sortBy must be one of: position, dateAdded, duration, episodeAvgRating");

        verifyNoInteractions(userListItemService);
    }

    @Test
    @DisplayName("[getUserListById] Should Sort Series Items By The Owner's Episode Average Rating")
    void shouldSortSeriesItemsByTheOwnersEpisodeAverageRating() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO lowRatedSeries = buildItemResponseDto(buildContentRef("100", ContentType.SERIES));
        UserListItemResponseDTO highRatedSeries = buildItemResponseDto(buildContentRef("200", ContentType.SERIES));
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(List.of(lowRatedSeries, highRatedSeries));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), eq(2L), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(list, invocation.getArgument(1)));
        when(diaryEntryRepository.findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(eq(lucasId), eq(Set.of("100", "200"))))
                .thenReturn(List.of(
                        buildEpisodeEntry("100", 1, 1, 4),
                        buildEpisodeEntry("100", 1, 2, 6),
                        buildEpisodeEntry("200", 1, 1, 10),
                        buildEpisodeEntry("200", 1, 2, 8)));

        UserListDetailedResponseDTO result = userListService.getUserListById(
                lucasId, list.getId(), null, null, "episodeAvgRating", "desc");

        assertThat(result.items()).containsExactly(highRatedSeries, lowRatedSeries);
    }

    @Test
    @DisplayName("[getUserListById] Should Sort Items With No Episode Ratings Last - Regardless Of Direction")
    void shouldSortItemsWithNoEpisodeRatingsLastRegardlessOfDirection() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO unratedMovie = buildItemResponseDto(buildContentRef("100", ContentType.MOVIE));
        UserListItemResponseDTO ratedSeries = buildItemResponseDto(buildContentRef("200", ContentType.SERIES));
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(List.of(unratedMovie, ratedSeries));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), eq(2L), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(list, invocation.getArgument(1)));
        when(diaryEntryRepository.findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(eq(lucasId), eq(Set.of("200"))))
                .thenReturn(List.of(buildEpisodeEntry("200", 1, 1, 9)));

        UserListDetailedResponseDTO ascResult = userListService.getUserListById(
                lucasId, list.getId(), null, null, "episodeAvgRating", "asc");
        UserListDetailedResponseDTO descResult = userListService.getUserListById(
                lucasId, list.getId(), null, null, "episodeAvgRating", "desc");

        assertThat(ascResult.items()).containsExactly(ratedSeries, unratedMovie);
        assertThat(descResult.items()).containsExactly(ratedSeries, unratedMovie);
    }

    @Test
    @DisplayName("[getUserListById] Should Use Only The Owner's Ratings - Not The Viewer's")
    void shouldUseOnlyTheOwnersRatingsNotTheViewersForEpisodeAvgRating() {
        UserList list = buildList(lucas, "Public list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO seriesItem = buildItemResponseDto(buildContentRef("100", ContentType.SERIES));
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(marinaId, list.getId())).thenReturn(List.of(seriesItem));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), eq(1L), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(list, invocation.getArgument(1)));

        userListService.getUserListById(marinaId, list.getId(), null, null, "episodeAvgRating", null);

        verify(diaryEntryRepository).findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(eq(lucasId), eq(Set.of("100")));
        verify(diaryEntryRepository, never()).findScoredEpisodeEntriesByUserIdAndSeriesTmdbIdIn(eq(marinaId), any());
    }

    private DiaryEntry buildEpisodeEntry(String seriesTmdbId, int seasonNumber, int episodeNumber, int score) {
        LocalDateTime now = LocalDateTime.now();
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .type(ContentType.EPISODE)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(lucas)
                .content(content)
                .score(score)
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserListItemResponseDTO buildItemResponseDtoWithContent(ContentType type, Integer runtimeMinutes, List<String> genres, int position) {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), "100", type, null, null, null, null, null, now, now, runtimeMinutes, genres);
        return new UserListItemResponseDTO(UUID.randomUUID(), content, null, position, null, now, now);
    }

    @Test
    @DisplayName("[getUserListById] Should Populate Watched Percentage - Using The Viewer's Own Watch History, Not The Owner's")
    void shouldPopulateWatchedPercentageUsingTheViewersOwnWatchHistoryNotTheOwnersOnGetById() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        List<UserListItemResponseDTO> items = List.of(buildItemResponseDto(buildContentRef("100", ContentType.MOVIE)));
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(marinaId, list.getId())).thenReturn(items);
        when(userListItemService.getWatchedPercentage(list.getId(), marinaId)).thenReturn(50.0);
        when(userListMapper.userListToDetailedResponseDto(list, items, 50.0, false, 1L, 0L, 0L, UserListItemScope.MOVIE_OR_SERIES)).thenReturn(buildDetailedResponseDto(list, items));

        userListService.getUserListById(marinaId, list.getId(), null, null, null, null);

        verify(userListItemService).getWatchedPercentage(list.getId(), marinaId);
        verify(userListItemService, never()).getWatchedPercentage(list.getId(), lucasId);
    }

    @Test
    @DisplayName("[getUserListById] Should Return Detailed List - When List Is Public And Viewer Is A Different User")
    void shouldReturnDetailedListWhenListIsPublicAndViewerIsADifferentUser() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(marinaId, list.getId())).thenReturn(List.of());
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenReturn(buildDetailedResponseDto(list, List.of()));

        UserListDetailedResponseDTO result = userListService.getUserListById(marinaId, list.getId(), null, null, null, null);

        assertThat(result).isNotNull();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getUserListById] Should Return Detailed List - When List Is Followers-Only And Viewer Follows The Owner")
    void shouldReturnDetailedListWhenListIsFollowersOnlyAndViewerFollowsTheOwner() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(true);
        when(userListItemService.getItems(marinaId, list.getId())).thenReturn(List.of());
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenReturn(buildDetailedResponseDto(list, List.of()));

        UserListDetailedResponseDTO result = userListService.getUserListById(marinaId, list.getId(), null, null, null, null);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("[getUserListById] Should Throw ForbiddenException - When List Is Followers-Only And Viewer Does Not Follow The Owner")
    void shouldThrowForbiddenExceptionWhenListIsFollowersOnlyAndViewerDoesNotFollowTheOwner() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> userListService.getUserListById(marinaId, list.getId(), null, null, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verifyNoInteractions(userListItemService);
    }

    @Test
    @DisplayName("[getUserListById] Should Throw ForbiddenException - When List Is Private And Viewer Is A Different User")
    void shouldThrowForbiddenExceptionWhenListIsPrivateAndViewerIsADifferentUser() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PRIVATE);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));

        assertThatThrownBy(() -> userListService.getUserListById(marinaId, list.getId(), null, null, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verifyNoInteractions(userListItemService);
    }

    @Test
    @DisplayName("[getUserListById] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnGetById() {
        UUID missingId = UUID.randomUUID();
        when(userListRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListService.getUserListById(lucasId, missingId, null, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");
    }

    @Test
    @DisplayName("[getUserListById] Should Pass The Resolved ItemScope To The Mapper - When List Has Content Items")
    void shouldPassTheResolvedItemScopeToTheMapperWhenListHasContentItems() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        UserListItemResponseDTO movieItem = buildItemResponseDto(buildContentRef("550", ContentType.MOVIE));
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));
        when(userListItemService.getItems(lucasId, list.getId())).thenReturn(List.of(movieItem));
        when(userListMapper.userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(),
                eq(UserListItemScope.MOVIE_OR_SERIES))).thenReturn(buildDetailedResponseDto(list, List.of(movieItem)));

        userListService.getUserListById(lucasId, list.getId(), null, null, null, null);

        verify(userListMapper).userListToDetailedResponseDto(eq(list), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(),
                eq(UserListItemScope.MOVIE_OR_SERIES));
    }

    // ---------- createUserList ----------

    @Test
    @DisplayName("[createUserList] Should Save New List With Provided Fields")
    void shouldSaveNewListWithProvidedFields() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        UserListResponseDTO result = userListService.createUserList(
                lucasId, new UserListCreationDTO("Best sci-fi of the 90s", "A curated list", UserListVisibility.PRIVATE));

        assertThat(result.name()).isEqualTo("Best sci-fi of the 90s");
        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getUser()).isEqualTo(lucas);
        assertThat(listCaptor.getValue().getName()).isEqualTo("Best sci-fi of the 90s");
        assertThat(listCaptor.getValue().getDescription()).isEqualTo("A curated list");
        assertThat(listCaptor.getValue().getVisibility()).isEqualTo(UserListVisibility.PRIVATE);
        assertThat(listCaptor.getValue().getCreatedAt()).isNotNull();
        assertThat(listCaptor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[createUserList] Should Assign Rank One Past The Current Ranked Lists Count")
    void shouldAssignRankOnePastTheCurrentRankedListsCountOnCreate() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(3L);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.createUserList(lucasId, new UserListCreationDTO("My list", null, null));

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getRank()).isEqualTo(4);
    }

    @Test
    @DisplayName("[createUserList] Should Map With Empty Preview And Zero Nested Lists Count - Without Querying Items")
    void shouldMapWithEmptyPreviewAndZeroNestedListsCountWithoutQueryingItemsOnCreate() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), eq(List.of()), eq(0L), eq(0.0), anyBoolean(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.createUserList(lucasId, new UserListCreationDTO("My list", null, null));

        verify(userListMapper).userListToResponseDto(any(UserList.class), eq(List.of()), eq(0L), eq(0.0), anyBoolean(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(userListItemService);
    }

    @Test
    @DisplayName("[createUserList] Should Default Visibility To Public - When Omitted")
    void shouldDefaultVisibilityToPublicWhenOmitted() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.createUserList(lucasId, new UserListCreationDTO("My list", null, null));

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getVisibility()).isEqualTo(UserListVisibility.PUBLIC);
    }

    @Test
    @DisplayName("[createUserList] Should Persist A Null Description - When Omitted")
    void shouldPersistANullDescriptionWhenOmitted() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.createUserList(lucasId, new UserListCreationDTO("My list", null, UserListVisibility.PUBLIC));

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getDescription()).isNull();
    }

    // ---------- createUserListWithItems ----------

    @Test
    @DisplayName("[createUserListWithItems] Should Create List And Add All Items In Order")
    void shouldCreateListAndAddAllItemsInOrderWhenPayloadIsValid() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> {
            UserList list = invocation.getArgument(0);
            list.setId(UUID.randomUUID());
            return list;
        });

        ContentRefCreationDTO movieRef = buildContentRef("100", ContentType.MOVIE);
        ContentRefCreationDTO seriesRef = buildContentRef("200", ContentType.SERIES);
        UserListItemResponseDTO movieItem = buildItemResponseDto(movieRef);
        UserListItemResponseDTO seriesItem = buildItemResponseDto(seriesRef);

        when(userListItemService.addItems(eq(lucasId), any(UUID.class), any(UserListItemBulkCreationDTO.class)))
                .thenReturn(List.of(movieItem, seriesItem));
        when(userListMapper.userListToDetailedResponseDto(any(UserList.class), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(invocation.getArgument(0), invocation.getArgument(1)));

        UserListDetailedResponseDTO result = userListService.createUserListWithItems(
                lucasId, new UserListBulkCreationDTO("Best sci-fi of the 90s", "A curated list", UserListVisibility.PRIVATE, List.of(movieRef, seriesRef)));

        assertThat(result.name()).isEqualTo("Best sci-fi of the 90s");
        assertThat(result.items()).containsExactly(movieItem, seriesItem);

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getVisibility()).isEqualTo(UserListVisibility.PRIVATE);

        verify(userListItemService).addItems(eq(lucasId), any(UUID.class), itemsCaptor.capture());
        assertThat(itemsCaptor.getValue().items()).containsExactly(movieRef, seriesRef);
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Populate Watched Percentage - From The Owner's Watch History")
    void shouldPopulateWatchedPercentageFromTheOwnersWatchHistoryOnBulkCreate() {
        UUID savedListId = UUID.randomUUID();
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> {
            UserList list = invocation.getArgument(0);
            list.setId(savedListId);
            return list;
        });
        ContentRefCreationDTO movieRef = buildContentRef("100", ContentType.MOVIE);
        when(userListItemService.addItems(eq(lucasId), any(UUID.class), any(UserListItemBulkCreationDTO.class)))
                .thenReturn(List.of(buildItemResponseDto(movieRef)));
        when(userListItemService.getWatchedPercentage(savedListId, lucasId)).thenReturn(100.0);
        when(userListMapper.userListToDetailedResponseDto(any(UserList.class), anyList(), eq(100.0), anyBoolean(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(invocation.getArgument(0), invocation.getArgument(1)));

        userListService.createUserListWithItems(
                lucasId, new UserListBulkCreationDTO("My list", null, null, List.of(movieRef)));

        verify(userListItemService).getWatchedPercentage(savedListId, lucasId);
        verify(userListMapper).userListToDetailedResponseDto(any(UserList.class), anyList(), eq(100.0), anyBoolean(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Default Visibility To Public - When Omitted")
    void shouldDefaultVisibilityToPublicWhenOmittedOnBulkCreate() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> {
            UserList list = invocation.getArgument(0);
            list.setId(UUID.randomUUID());
            return list;
        });
        ContentRefCreationDTO movieRef = buildContentRef("100", ContentType.MOVIE);
        when(userListItemService.addItems(eq(lucasId), any(UUID.class), any(UserListItemBulkCreationDTO.class)))
                .thenReturn(List.of(buildItemResponseDto(movieRef)));
        when(userListMapper.userListToDetailedResponseDto(any(UserList.class), anyList(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer(invocation -> buildDetailedResponseDto(invocation.getArgument(0), invocation.getArgument(1)));

        userListService.createUserListWithItems(lucasId, new UserListBulkCreationDTO("My list", null, null, List.of(movieRef)));

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getVisibility()).isEqualTo(UserListVisibility.PUBLIC);
    }

    @Test
    @DisplayName("[createUserListWithItems] Should Propagate Exception - When Adding Items Fails")
    void shouldPropagateExceptionWhenAddingItemsFails() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> {
            UserList list = invocation.getArgument(0);
            list.setId(UUID.randomUUID());
            return list;
        });
        ContentRefCreationDTO movieRef = buildContentRef("100", ContentType.MOVIE);
        ContentRefCreationDTO duplicateRef = buildContentRef("100", ContentType.MOVIE);
        when(userListItemService.addItems(eq(lucasId), any(UUID.class), any(UserListItemBulkCreationDTO.class)))
                .thenThrow(new ConflictException("This content is already in the list"));

        assertThatThrownBy(() -> userListService.createUserListWithItems(
                lucasId, new UserListBulkCreationDTO("My list", null, UserListVisibility.PUBLIC, List.of(movieRef, duplicateRef))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already in the list");

        verifyNoInteractions(userListMapper);
    }

    // ---------- updateUserList ----------

    @Test
    @DisplayName("[updateUserList] Should Update Name, Description And Visibility - When All Provided")
    void shouldUpdateNameDescriptionAndVisibilityWhenAllProvided() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        UserListResponseDTO result = userListService.updateUserList(
                lucasId, existing.getId(), new UserListPatchDTO("New name", "New description", UserListVisibility.PRIVATE));

        assertThat(result.name()).isEqualTo("New name");
        assertThat(existing.getName()).isEqualTo("New name");
        assertThat(existing.getDescription()).isEqualTo("New description");
        assertThat(existing.getVisibility()).isEqualTo(UserListVisibility.PRIVATE);
    }

    @Test
    @DisplayName("[updateUserList] Should Populate Preview Items And Nested Lists Count - From The List's Actual Items")
    void shouldPopulatePreviewItemsAndNestedListsCountFromTheListsActualItemsOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        List<ContentRefDTO> previewItems = List.of(
                new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, null, null));
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListItemService.getPreviewItems(existing.getId())).thenReturn(previewItems);
        when(userListItemService.countNestedLists(existing.getId())).thenReturn(1L);
        when(userListMapper.userListToResponseDto(existing, previewItems, 1L, 0.0, false, 0L, 0L, 0L, null)).thenReturn(buildResponseDto(existing));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null));

        verify(userListMapper).userListToResponseDto(existing, previewItems, 1L, 0.0, false, 0L, 0L, 0L, null);
    }

    @Test
    @DisplayName("[updateUserList] Should Query And Pass The Real ItemScope To The Mapper - When List Has Content Items")
    void shouldQueryAndPassTheRealItemScopeToTheMapperOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListItemService.getItemScope(existing.getId())).thenReturn(UserListItemScope.MOVIE_OR_SERIES);
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(),
                eq(UserListItemScope.MOVIE_OR_SERIES))).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null));

        verify(userListMapper).userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(),
                eq(UserListItemScope.MOVIE_OR_SERIES));
    }

    @Test
    @DisplayName("[updateUserList] Should Not Change Any Field - When All Patch Fields Are Null")
    void shouldNotChangeAnyFieldWhenAllPatchFieldsAreNullOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null));

        assertThat(existing.getName()).isEqualTo("Old name");
        assertThat(existing.getDescription()).isEqualTo("Old description");
        assertThat(existing.getVisibility()).isEqualTo(UserListVisibility.PUBLIC);
    }

    @Test
    @DisplayName("[updateUserList] Should Change Name - When A Different Value Is Provided")
    void shouldChangeNameWhenDifferentValueProvidedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO("  New name  ", null, null));

        assertThat(existing.getName()).isEqualTo("New name");
    }

    @Test
    @DisplayName("[updateUserList] Should Keep Same Name - When Same Value Is Provided")
    void shouldKeepSameNameWhenSameValueProvidedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO("Old name", null, null));

        assertThat(existing.getName()).isEqualTo("Old name");
    }

    @Test
    @DisplayName("[updateUserList] Should Change Description - When A Different Value Is Provided")
    void shouldChangeDescriptionWhenDifferentValueProvidedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, "New description", null));

        assertThat(existing.getDescription()).isEqualTo("New description");
    }

    @Test
    @DisplayName("[updateUserList] Should Keep Same Description - When Same Value Is Provided")
    void shouldKeepSameDescriptionWhenSameValueProvidedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, "Old description", null));

        assertThat(existing.getDescription()).isEqualTo("Old description");
    }

    @Test
    @DisplayName("[updateUserList] Should Change Visibility - When A Different Value Is Provided")
    void shouldChangeVisibilityWhenDifferentValueProvidedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, UserListVisibility.PRIVATE));

        assertThat(existing.getVisibility()).isEqualTo(UserListVisibility.PRIVATE);
    }

    @Test
    @DisplayName("[updateUserList] Should Keep Same Visibility - When Same Value Is Provided")
    void shouldKeepSameVisibilityWhenSameValueProvidedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", "Old description", UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, UserListVisibility.PUBLIC));

        assertThat(existing.getVisibility()).isEqualTo(UserListVisibility.PUBLIC);
    }

    @Test
    @DisplayName("[updateUserList] Should Throw BadRequestException - When Name Is Blank")
    void shouldThrowBadRequestExceptionWhenNameIsBlankOnUpdate() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, existing.getId(), new UserListPatchDTO("   ", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Name must not be blank");

        verify(userListRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUserList] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnUpdate() {
        UUID missingId = UUID.randomUUID();
        when(userListRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, missingId, new UserListPatchDTO("Name", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUserList] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnUpdate() {
        UserList marinasList = buildList(marina, "Marina's list", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(marinasList.getId())).thenReturn(Optional.of(marinasList));

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, marinasList.getId(), new UserListPatchDTO("Name", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUserList] Should Move Rank Forward - When Reordering To An Earlier Position")
    void shouldMoveRankForwardWhenReorderingToAnEarlierPosition() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        existing.setRank(5);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(5L);
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null, 2));

        assertThat(existing.getRank()).isEqualTo(2);
        verify(userListRepository).parkRanksInRange(lucasId, 2, 4, UserListServiceImpl.RANK_PARK_OFFSET);
        verify(userListRepository).settleParkedRanks(lucasId, UserListServiceImpl.RANK_PARK_OFFSET, 1);
    }

    @Test
    @DisplayName("[updateUserList] Should Move Rank Backward - When Reordering To A Later Position")
    void shouldMoveRankBackwardWhenReorderingToALaterPosition() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        existing.setRank(1);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(5L);
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null, 4));

        assertThat(existing.getRank()).isEqualTo(4);
        verify(userListRepository).parkRanksInRange(lucasId, 2, 4, UserListServiceImpl.RANK_PARK_OFFSET);
        verify(userListRepository).settleParkedRanks(lucasId, UserListServiceImpl.RANK_PARK_OFFSET, -1);
    }

    @Test
    @DisplayName("[updateUserList] Should Adopt A Null-Rank List Into The Ranked Sequence Before Moving")
    void shouldAdoptANullRankListIntoTheRankedSequenceBeforeMoving() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(3L, 4L);
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null, 1));

        assertThat(existing.getRank()).isEqualTo(1);
        verify(userListRepository).parkRanksInRange(lucasId, 1, 3, UserListServiceImpl.RANK_PARK_OFFSET);
    }

    @Test
    @DisplayName("[updateUserList] Should Be A No-Op - When Requested Rank Equals The Current Rank")
    void shouldBeANoOpWhenRequestedRankEqualsTheCurrentRank() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        existing.setRank(2);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(5L);
        when(userListRepository.saveAndFlush(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class), any(), anyLong(), anyDouble(), anyBoolean(), anyLong(), anyLong(), anyLong(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListPatchDTO(null, null, null, 2));

        assertThat(existing.getRank()).isEqualTo(2);
        verify(userListRepository, never()).parkRanksInRange(any(), anyInt(), anyInt(), anyInt());
        verify(userListRepository, never()).settleParkedRanks(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("[updateUserList] Should Throw BadRequestException - When Rank Is Greater Than The Number Of Ranked Lists")
    void shouldThrowBadRequestExceptionWhenRankIsGreaterThanTheNumberOfRankedLists() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        existing.setRank(1);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(2L);

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, existing.getId(), new UserListPatchDTO(null, null, null, 5)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("rank cannot be greater than 2, the last rank among your lists");

        verify(userListRepository, never()).parkRanksInRange(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("[updateUserList] Should Throw ConflictException - When SaveAndFlush Fails Due To A Concurrent Update")
    void shouldThrowConflictExceptionWhenSaveAndFlushFailsDueToAConcurrentUpdate() {
        UserList existing = buildList(lucas, "Old name", null, UserListVisibility.PUBLIC);
        existing.setRank(1);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.countByUserIdAndRankIsNotNull(lucasId)).thenReturn(3L);
        when(userListRepository.saveAndFlush(any(UserList.class)))
                .thenThrow(new DataIntegrityViolationException("constraint violated"));

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, existing.getId(), new UserListPatchDTO(null, null, null, 2)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("List could not be reordered due to a concurrent update");
    }

    // ---------- deleteUserList ----------

    @Test
    @DisplayName("[deleteUserList] Should Delete The List - When Owned By The User")
    void shouldDeleteTheListWhenOwnedByTheUser() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));

        userListService.deleteUserList(lucasId, list.getId());

        verify(userListRepository).delete(list);
    }

    @Test
    @DisplayName("[deleteUserList] Should Remove Items Referencing This List As A Nested Child - Before Deleting It")
    void shouldRemoveItemsReferencingThisListAsANestedChildBeforeDeletingIt() {
        UserList list = buildList(lucas, "My list", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));

        userListService.deleteUserList(lucasId, list.getId());

        InOrder inOrder = inOrder(userListItemService, userListRepository);
        inOrder.verify(userListItemService).removeItemsReferencingChildList(list.getId());
        inOrder.verify(userListRepository).delete(list);
    }

    @Test
    @DisplayName("[deleteUserList] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnDelete() {
        UUID missingId = UUID.randomUUID();
        when(userListRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListService.deleteUserList(lucasId, missingId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).delete(any());
        verifyNoInteractions(userListItemService);
    }

    @Test
    @DisplayName("[deleteUserList] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnDelete() {
        UserList marinasList = buildList(marina, "Marina's list", null, UserListVisibility.PUBLIC);
        when(userListRepository.findById(marinasList.getId())).thenReturn(Optional.of(marinasList));

        assertThatThrownBy(() -> userListService.deleteUserList(lucasId, marinasList.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).delete(any());
        verifyNoInteractions(userListItemService);
    }

    // ---------- helpers ----------

    private void stubEmptyOwnListsPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());
    }

    private User buildUser(UUID id, String username, boolean isProfilePublic) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@email.com")
                .password("hashed_password")
                .isProfilePublic(isProfilePublic)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private UserList buildList(User user, String name, String description, UserListVisibility visibility) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name(name)
                .description(description)
                .visibility(visibility)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserListResponseDTO buildResponseDto(UserList list) {
        return new UserListResponseDTO(
                list.getId(), list.getName(), list.getDescription(),
                list.getVisibility(), 0.0, list.getCreatedAt(), list.getUpdatedAt(), List.of(), 0L, 0, false, 0L, 0L, 0L, list.getRank(), null);
    }

    private UserListDetailedResponseDTO buildDetailedResponseDto(UserList list, List<UserListItemResponseDTO> items) {
        return new UserListDetailedResponseDTO(
                list.getId(), list.getName(), list.getDescription(),
                list.getVisibility(), 0.0, list.getCreatedAt(), list.getUpdatedAt(), items, 0, false, 0L, 0L, 0L, list.getRank(), null);
    }

    private ContentRefCreationDTO buildContentRef(String tmdbId, ContentType type) {
        return new ContentRefCreationDTO(tmdbId, type, null, null, null, null, null);
    }

    private UserListItemResponseDTO buildItemResponseDto(ContentRefCreationDTO content) {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO contentRefDTO = new ContentRefDTO(
                UUID.randomUUID(), content.tmdbId(), content.type(), content.seriesTmdbId(),
                content.seasonNumber(), content.episodeNumber(), null, null, now, now);
        return new UserListItemResponseDTO(UUID.randomUUID(), contentRefDTO, null, 1, null, now, now);
    }
}
