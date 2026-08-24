package com.watchwise.watchwise_api.userlist.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemPatchDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.mapper.UserListItemMapper;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserListItemServiceImplTest {

    @Mock
    private UserListItemRepository userListItemRepository;

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private UserListItemMapper userListItemMapper;

    @InjectMocks
    private UserListItemServiceImpl userListItemService;

    @Captor
    private ArgumentCaptor<UserListItem> itemCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;
    private UUID listId;
    private UserList scifi;
    private Content fightClub;

    @BeforeEach
    void setUp() {
        lucasId = UUID.randomUUID();
        marinaId = UUID.randomUUID();

        lucas = User.builder()
                .id(lucasId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        listId = UUID.randomUUID();
        scifi = buildUserList(listId, lucas, "Best sci-fi of the 90s", UserListVisibility.PUBLIC);
        fightClub = buildContent("550", ContentType.MOVIE);
    }

    // ---------- getItems ----------

    @Test
    @DisplayName("[getItems] Should Keep ChildList Metadata - When Nested List Is Still Visible To The Viewer")
    void shouldKeepChildListMetadataWhenNestedListIsStillVisibleToTheViewer() {
        UserList childList = buildUserList(UUID.randomUUID(), lucas, "Nested", UserListVisibility.PUBLIC);
        UserListItem item = buildChildListItem(scifi, childList, 1);
        UserListItemResponseDTO mapped = new UserListItemResponseDTO(
                item.getId(), null, buildPreviewDto(childList), 1, null, LocalDateTime.now(), LocalDateTime.now());
        when(userListItemRepository.findByUserListIdWithContentAndChildListOrderByPositionAsc(listId))
                .thenReturn(List.of(item));
        when(userListItemMapper.userListItemToResponseDto(item)).thenReturn(mapped);

        List<UserListItemResponseDTO> result = userListItemService.getItems(marinaId, listId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).childList()).isEqualTo(mapped.childList());
    }

    @Test
    @DisplayName("[getItems] Should Mask ChildList Metadata - When Nested List Became Private After Being Added")
    void shouldMaskChildListMetadataWhenNestedListBecamePrivateAfterBeingAdded() {
        UserList childList = buildUserList(UUID.randomUUID(), marina, "Now private", UserListVisibility.PRIVATE);
        UserListItem item = buildChildListItem(scifi, childList, 1);
        UserListItemResponseDTO mapped = new UserListItemResponseDTO(
                item.getId(), null, buildPreviewDto(childList), 1, "desc", LocalDateTime.now(), LocalDateTime.now());
        when(userListItemRepository.findByUserListIdWithContentAndChildListOrderByPositionAsc(listId))
                .thenReturn(List.of(item));
        when(userListItemMapper.userListItemToResponseDto(item)).thenReturn(mapped);

        List<UserListItemResponseDTO> result = userListItemService.getItems(lucasId, listId);

        assertThat(result).hasSize(1);
        UserListItemResponseDTO dto = result.get(0);
        assertThat(dto.childList()).isNull();
        assertThat(dto.id()).isEqualTo(mapped.id());
        assertThat(dto.position()).isEqualTo(mapped.position());
        assertThat(dto.description()).isEqualTo(mapped.description());
    }

    @Test
    @DisplayName("[getItems] Should Keep ChildList Metadata - When Viewer Is The Nested List's Owner")
    void shouldKeepChildListMetadataWhenViewerIsTheNestedListsOwner() {
        UserList childList = buildUserList(UUID.randomUUID(), marina, "Marina's private list", UserListVisibility.PRIVATE);
        UserListItem item = buildChildListItem(scifi, childList, 1);
        UserListItemResponseDTO mapped = new UserListItemResponseDTO(
                item.getId(), null, buildPreviewDto(childList), 1, null, LocalDateTime.now(), LocalDateTime.now());
        when(userListItemRepository.findByUserListIdWithContentAndChildListOrderByPositionAsc(listId))
                .thenReturn(List.of(item));
        when(userListItemMapper.userListItemToResponseDto(item)).thenReturn(mapped);

        List<UserListItemResponseDTO> result = userListItemService.getItems(marinaId, listId);

        assertThat(result.get(0).childList()).isEqualTo(mapped.childList());
    }

    @Test
    @DisplayName("[getItems] Should Not Touch Content Items - When Item Has No ChildList")
    void shouldNotTouchContentItemsWhenItemHasNoChildList() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        UserListItemResponseDTO mapped = new UserListItemResponseDTO(
                item.getId(), buildContentRefDto(fightClub), null, 1, null, LocalDateTime.now(), LocalDateTime.now());
        when(userListItemRepository.findByUserListIdWithContentAndChildListOrderByPositionAsc(listId))
                .thenReturn(List.of(item));
        when(userListItemMapper.userListItemToResponseDto(item)).thenReturn(mapped);

        List<UserListItemResponseDTO> result = userListItemService.getItems(marinaId, listId);

        assertThat(result.get(0)).isEqualTo(mapped);
        verifyNoInteractions(followerRepository);
    }

    // ---------- getPreviewItems / getPreviewItemsByListIds ----------

    @Test
    @DisplayName("[getPreviewItems] Should Return Mapped Content Refs Ordered By Position - When List Has Content Items")
    void shouldReturnMappedContentRefsOrderedByPositionWhenListHasContentItems() {
        UserListItem item1 = buildContentItem(scifi, fightClub, 1);
        ContentRefDTO contentRef1 = buildContentRefDto(fightClub);
        when(userListItemRepository.findContentItemsByUserListIdInOrderByPosition(List.of(listId)))
                .thenReturn(List.of(item1));
        when(userListItemMapper.userListItemToResponseDto(item1))
                .thenReturn(new UserListItemResponseDTO(item1.getId(), contentRef1, null, 1, null, LocalDateTime.now(), LocalDateTime.now()));

        List<ContentRefDTO> result = userListItemService.getPreviewItems(listId);

        assertThat(result).containsExactly(contentRef1);
    }

    @Test
    @DisplayName("[getPreviewItems] Should Return Empty List - When List Has No Content Items")
    void shouldReturnEmptyListWhenListHasNoContentItemsForPreview() {
        when(userListItemRepository.findContentItemsByUserListIdInOrderByPosition(List.of(listId)))
                .thenReturn(List.of());

        List<ContentRefDTO> result = userListItemService.getPreviewItems(listId);

        assertThat(result).isEmpty();
        verifyNoInteractions(userListItemMapper);
    }

    @Test
    @DisplayName("[getPreviewItemsByListIds] Should Group Items By Their Own List And Cap Each At Five - When Multiple Lists Are Requested")
    void shouldGroupItemsByTheirOwnListAndCapEachAtFiveWhenMultipleListsAreRequested() {
        UUID otherListId = UUID.randomUUID();
        UserList horror = buildUserList(otherListId, lucas, "Underrated horror", UserListVisibility.PUBLIC);

        List<Content> extraContents = List.of(
                buildContent("13", ContentType.MOVIE), buildContent("14", ContentType.MOVIE),
                buildContent("15", ContentType.MOVIE), buildContent("16", ContentType.MOVIE),
                buildContent("17", ContentType.MOVIE));
        List<UserListItem> scifiItems = extraContents.stream()
                .map(content -> buildContentItem(scifi, content, 1))
                .toList();
        UserListItem horrorItem = buildContentItem(horror, fightClub, 1);

        List<UserListItem> allItems = new ArrayList<>(scifiItems);
        allItems.add(horrorItem);

        when(userListItemRepository.findContentItemsByUserListIdInOrderByPosition(List.of(listId, otherListId)))
                .thenReturn(allItems);
        for (UserListItem item : allItems) {
            when(userListItemMapper.userListItemToResponseDto(item)).thenReturn(
                    new UserListItemResponseDTO(item.getId(), buildContentRefDto(item.getContent()), null, 1, null, LocalDateTime.now(), LocalDateTime.now()));
        }

        Map<UUID, List<ContentRefDTO>> result = userListItemService.getPreviewItemsByListIds(List.of(listId, otherListId));

        assertThat(result.get(listId)).hasSize(5);
        assertThat(result.get(otherListId)).hasSize(1);
    }

    @Test
    @DisplayName("[getPreviewItemsByListIds] Should Return Empty Map - When No List Ids Are Given")
    void shouldReturnEmptyMapWhenNoListIdsAreGivenForPreviewItems() {
        Map<UUID, List<ContentRefDTO>> result = userListItemService.getPreviewItemsByListIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(userListItemRepository);
    }

    // ---------- countNestedLists / countNestedListsByListIds ----------

    @Test
    @DisplayName("[countNestedLists] Should Return The Repository Count - When List Has Nested List Items")
    void shouldReturnTheRepositoryCountWhenListHasNestedListItems() {
        when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId)))
                .thenReturn(List.of(buildUserListCount(listId, 3L)));

        long result = userListItemService.countNestedLists(listId);

        assertThat(result).isEqualTo(3L);
    }

    @Test
    @DisplayName("[countNestedLists] Should Return Zero - When List Has No Nested List Items")
    void shouldReturnZeroWhenListHasNoNestedListItems() {
        when(userListItemRepository.countNestedListsByUserListIdIn(List.of(listId))).thenReturn(List.of());

        long result = userListItemService.countNestedLists(listId);

        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("[countNestedListsByListIds] Should Return Empty Map - When No List Ids Are Given")
    void shouldReturnEmptyMapWhenNoListIdsAreGivenForNestedListsCount() {
        Map<UUID, Long> result = userListItemService.countNestedListsByListIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(userListItemRepository);
    }

    // ---------- getWatchedPercentage / getWatchedPercentagesByListIds ----------

    @Test
    @DisplayName("[getWatchedPercentage] Should Return Zero - When List Has No Content Items")
    void shouldReturnZeroWhenListHasNoContentItems() {
        when(userListItemRepository.countContentItemsByUserListIdIn(List.of(listId))).thenReturn(List.of());

        double result = userListItemService.getWatchedPercentage(listId, lucasId);

        assertThat(result).isEqualTo(0.0);
        verify(userListItemRepository, never()).countWatchedContentItemsByUserListIdIn(any(), any());
    }

    @Test
    @DisplayName("[getWatchedPercentage] Should Return The Proportion Watched - When Some Items Are Watched")
    void shouldReturnTheProportionWatchedWhenSomeItemsAreWatched() {
        when(userListItemRepository.countContentItemsByUserListIdIn(List.of(listId)))
                .thenReturn(List.of(buildUserListCount(listId, 4L)));
        when(userListItemRepository.countWatchedContentItemsByUserListIdIn(List.of(listId), lucasId))
                .thenReturn(List.of(buildUserListCount(listId, 1L)));

        double result = userListItemService.getWatchedPercentage(listId, lucasId);

        assertThat(result).isEqualTo(25.0);
    }

    @Test
    @DisplayName("[getWatchedPercentage] Should Return One Hundred - When All Items Are Watched")
    void shouldReturnOneHundredWhenAllItemsAreWatched() {
        when(userListItemRepository.countContentItemsByUserListIdIn(List.of(listId)))
                .thenReturn(List.of(buildUserListCount(listId, 2L)));
        when(userListItemRepository.countWatchedContentItemsByUserListIdIn(List.of(listId), lucasId))
                .thenReturn(List.of(buildUserListCount(listId, 2L)));

        double result = userListItemService.getWatchedPercentage(listId, lucasId);

        assertThat(result).isEqualTo(100.0);
    }

    @Test
    @DisplayName("[getWatchedPercentage] Should Return Zero - When No Items Are Watched")
    void shouldReturnZeroWhenNoItemsAreWatched() {
        when(userListItemRepository.countContentItemsByUserListIdIn(List.of(listId)))
                .thenReturn(List.of(buildUserListCount(listId, 3L)));
        when(userListItemRepository.countWatchedContentItemsByUserListIdIn(List.of(listId), lucasId))
                .thenReturn(List.of());

        double result = userListItemService.getWatchedPercentage(listId, lucasId);

        assertThat(result).isEqualTo(0.0);
    }

    @Test
    @DisplayName("[getWatchedPercentagesByListIds] Should Compute Percentage Independently Per List - When Multiple Lists Are Requested")
    void shouldComputePercentageIndependentlyPerListWhenMultipleListsAreRequested() {
        UUID otherListId = UUID.randomUUID();
        when(userListItemRepository.countContentItemsByUserListIdIn(List.of(listId, otherListId)))
                .thenReturn(List.of(buildUserListCount(listId, 4L), buildUserListCount(otherListId, 2L)));
        when(userListItemRepository.countWatchedContentItemsByUserListIdIn(List.of(listId, otherListId), lucasId))
                .thenReturn(List.of(buildUserListCount(listId, 1L), buildUserListCount(otherListId, 2L)));

        Map<UUID, Double> result = userListItemService.getWatchedPercentagesByListIds(List.of(listId, otherListId), lucasId);

        assertThat(result.get(listId)).isEqualTo(25.0);
        assertThat(result.get(otherListId)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("[getWatchedPercentagesByListIds] Should Return Empty Map - When No List Ids Are Given")
    void shouldReturnEmptyMapWhenNoListIdsAreGivenForWatchedPercentages() {
        Map<UUID, Double> result = userListItemService.getWatchedPercentagesByListIds(List.of(), lucasId);

        assertThat(result).isEmpty();
        verifyNoInteractions(userListItemRepository);
    }

    @Test
    @DisplayName("[addItem] Should Insert At Position One - When List Is Empty")
    void shouldInsertAtPositionOneWhenListIsEmpty() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        UserListItemResponseDTO expectedDto = buildResponseDto();
        when(userListItemMapper.userListItemToResponseDto(any(UserListItem.class))).thenReturn(expectedDto);

        UserListItemResponseDTO result = userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null));

        assertThat(result).isEqualTo(expectedDto);
        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPosition()).isEqualTo(1);
        assertThat(itemCaptor.getValue().getContent()).isEqualTo(fightClub);
        assertThat(itemCaptor.getValue().getChildList()).isNull();
    }

    @Test
    @DisplayName("[addItem] Should Insert At Next Free Position - When List Already Has Items And No Position Given")
    void shouldInsertAtNextFreePositionWhenListAlreadyHasItemsAndNoPositionGiven() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(2L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPosition()).isEqualTo(3);
        verify(userListItemRepository, never()).delete(any());
        verify(userListItemRepository, never()).parkPositionsInRange(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("[addItem] Should Shift Existing Items Forward - When An Explicit Position Is Given")
    void shouldShiftExistingItemsForwardWhenAnExplicitPositionIsGiven() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(2L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, 1, null));

        verify(userListItemRepository).parkPositionsInRange(
                listId, 1, Integer.MAX_VALUE, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).settleParkedPositions(
                listId, UserListItemServiceImpl.POSITION_PARK_OFFSET, 1);
        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When Position Is Greater Than The Next Free Position")
    void shouldThrowBadRequestExceptionWhenPositionIsGreaterThanTheNextFreePosition() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, 2, null)))
                .isInstanceOf(BadRequestException.class);

        verify(userListItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("[addItem] Should Insert A Nested List Item - When ChildListId Is Provided And List Is Owned By The Caller")
    void shouldInsertANestedListItemWhenChildListIdIsProvidedAndListIsOwnedByTheCaller() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, lucas, "List of lists' target", UserListVisibility.PUBLIC);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)).thenReturn(false);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getChildList()).isEqualTo(childList);
        assertThat(itemCaptor.getValue().getContent()).isNull();
        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[addItem] Should Insert A Nested List Item - When ChildListId References Another User's Public List")
    void shouldInsertANestedListItemWhenChildListIdReferencesAnotherUsersPublicList() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, marina, "Marina's public list", UserListVisibility.PUBLIC);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)).thenReturn(false);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getChildList()).isEqualTo(childList);
    }

    @Test
    @DisplayName("[addItem] Should Throw ForbiddenException - When ChildListId References Another User's Private List")
    void shouldThrowForbiddenExceptionWhenChildListIdReferencesAnotherUsersPrivateList() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, marina, "Marina's private list", UserListVisibility.PRIVATE);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(userListItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("[addItem] Should Insert A Nested List Item - When ChildListId References A Followers-Only List And Caller Follows Its Owner")
    void shouldInsertANestedListItemWhenChildListIdReferencesAFollowersOnlyListAndCallerFollowsItsOwner() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, marina, "Marina's followers-only list", UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED)).thenReturn(true);
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)).thenReturn(false);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getChildList()).isEqualTo(childList);
    }

    @Test
    @DisplayName("[addItem] Should Throw ForbiddenException - When ChildListId References A Followers-Only List And Caller Does Not Follow Its Owner")
    void shouldThrowForbiddenExceptionWhenChildListIdReferencesAFollowersOnlyListAndCallerDoesNotFollowItsOwner() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, marina, "Marina's followers-only list", UserListVisibility.FOLLOWERS);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This list is private");

        verify(userListItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("[addItem] Should Throw NotFoundException - When ChildListId Does Not Exist")
    void shouldThrowNotFoundExceptionWhenChildListIdDoesNotExist() {
        UUID childListId = UUID.randomUUID();
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When ChildListId Is The Item's Own List")
    void shouldThrowBadRequestExceptionWhenChildListIdIsTheItemsOwnList() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, listId, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A list cannot reference itself");
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When ChildListId Already Contains Nested Lists")
    void shouldThrowBadRequestExceptionWhenChildListIdAlreadyContainsNestedLists() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, lucas, "Already a list of lists", UserListVisibility.PUBLIC);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)).thenReturn(true);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("nesting depth is limited to one level");
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When Parent List Is Already Nested Inside Another List")
    void shouldThrowBadRequestExceptionWhenParentListIsAlreadyNestedInsideAnotherList() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, lucas, "Would-be grandchild list", UserListVisibility.PUBLIC);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(userListItemRepository.existsByChildListId(listId)).thenReturn(true);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("nesting depth is limited to one level");

        verify(userListItemRepository, never()).existsByUserListIdAndChildListIdIsNotNull(childListId);
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When List Already Contains Content Items And ChildListId Is Given")
    void shouldThrowBadRequestExceptionWhenListAlreadyContainsContentItemsAndChildListIdIsGiven() {
        UUID childListId = UUID.randomUUID();
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(true);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot also contain nested lists");

        verify(userListRepository, never()).findById(childListId);
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When List Already Contains Nested Lists And Content Is Given")
    void shouldThrowBadRequestExceptionWhenListAlreadyContainsNestedListsAndContentIsGiven() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(true);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot also contain content items");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When Both Content And ChildListId Are Provided")
    void shouldThrowBadRequestExceptionWhenBothContentAndChildListIdAreProvided() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), UUID.randomUUID(), null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Exactly one of content or childListId must be provided");

        verifyNoInteractions(userListItemRepository, contentService);
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When Neither Content Nor ChildListId Is Provided")
    void shouldThrowBadRequestExceptionWhenNeitherContentNorChildListIdIsProvided() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Exactly one of content or childListId must be provided");
    }

    @Test
    @DisplayName("[addItem] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExist() {
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[addItem] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUser() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.addItem(
                marinaId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Specific Message - When Content Is Already In The List")
    void shouldThrowConflictExceptionWithSpecificMessageWhenContentIsAlreadyInTheList() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_user_list_items_user_list_id_content_id"));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already in the list");
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Specific Message - When List Is Already Nested In The List")
    void shouldThrowConflictExceptionWithSpecificMessageWhenListIsAlreadyNestedInTheList() {
        UUID childListId = UUID.randomUUID();
        UserList childList = buildUserList(childListId, lucas, "Nested", UserListVisibility.PUBLIC);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndContentIdIsNotNull(listId)).thenReturn(false);
        when(userListRepository.findById(childListId)).thenReturn(Optional.of(childList));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(childListId)).thenReturn(false);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_user_list_items_user_list_id_child_list_id"));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(null, childListId, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This list is already nested in the list");
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Specific Message - When Position Was Just Taken By A Concurrent Insert")
    void shouldThrowConflictExceptionWithSpecificMessageWhenPositionWasJustTakenByAConcurrentInsert() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_user_list_items_user_list_id_position"));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This position was just taken by a concurrent insert");
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Generic Message - When Constraint Name Is Unknown")
    void shouldThrowConflictExceptionWithGenericMessageWhenConstraintNameIsUnknown() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_some_other_constraint"));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this item into the list");
    }

    @Test
    @DisplayName("[addItem] Should Log The Original Exception - When Constraint Name Is Unknown")
    void shouldLogTheOriginalExceptionWhenConstraintNameIsUnknown() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception = buildDataIntegrityViolationException("uq_some_other_constraint");
        when(userListItemRepository.save(any(UserListItem.class))).thenThrow(exception);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UserListItemServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> userListItemService.addItem(
                    lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                    .isInstanceOf(ConflictException.class);

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("uq_some_other_constraint");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(exception.getMessage());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Generic Message - When Cause Is Not ConstraintViolationException")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNotConstraintViolationException() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("generic db error", new RuntimeException("unexpected cause"));
        when(userListItemRepository.save(any(UserListItem.class))).thenThrow(exception);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this item into the list");
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Generic Message - When Cause Is Null")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNull() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("no cause here");
        when(userListItemRepository.save(any(UserListItem.class))).thenThrow(exception);

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this item into the list");
    }

    @Test
    @DisplayName("[addItems] Should Insert All Items In The Order Sent - When All Items Are Valid")
    void shouldInsertAllItemsInTheOrderSentWhenAllItemsAreValid() {
        Content contentA = buildContent("550", ContentType.MOVIE);
        Content contentB = buildContent("551", ContentType.MOVIE);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        when(contentService.getOrCreateReference(contentRefCreation("550"))).thenReturn(buildContentRefDto(contentA));
        when(contentService.getOrCreateReference(contentRefCreation("551"))).thenReturn(buildContentRefDto(contentB));
        when(contentRepository.getReferenceById(contentA.getId())).thenReturn(contentA);
        when(contentRepository.getReferenceById(contentB.getId())).thenReturn(contentB);

        List<UserListItem> persisted = new ArrayList<>();
        when(userListItemRepository.countByUserListId(listId))
                .thenAnswer(invocation -> (long) persisted.size());
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> {
            UserListItem item = invocation.getArgument(0);
            persisted.add(item);
            return item;
        });
        when(userListItemMapper.userListItemToResponseDto(any(UserListItem.class))).thenAnswer(invocation -> {
            UserListItem item = invocation.getArgument(0);
            return new UserListItemResponseDTO(UUID.randomUUID(), null, null, item.getPosition(), null, LocalDateTime.now(), LocalDateTime.now());
        });

        List<UserListItemResponseDTO> result = userListItemService.addItems(lucasId, listId,
                new UserListItemBulkCreationDTO(List.of(contentRefCreation("550"), contentRefCreation("551"))));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).position()).isEqualTo(1);
        assertThat(result.get(1).position()).isEqualTo(2);
        assertThat(persisted).extracting(UserListItem::getContent).containsExactly(contentA, contentB);
    }

    @Test
    @DisplayName("[addItems] Should Throw ConflictException And Stop - When An Item Duplicates Another Already Inserted In The Same Call")
    void shouldThrowConflictExceptionAndStopWhenAnItemDuplicatesAnotherAlreadyInsertedInTheSameCall() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.countByUserListId(listId)).thenReturn(0L);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(buildDataIntegrityViolationException("uq_user_list_items_user_list_id_content_id"));

        assertThatThrownBy(() -> userListItemService.addItems(lucasId, listId,
                new UserListItemBulkCreationDTO(List.of(contentRefCreation("550"), contentRefCreation("550")))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already in the list");

        verify(userListItemRepository, times(2)).save(any(UserListItem.class));
    }

    @Test
    @DisplayName("[addItems] Should Throw BadRequestException - When List Already Contains Nested Lists")
    void shouldThrowBadRequestExceptionWhenListAlreadyContainsNestedListsOnAddItems() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(true);

        assertThatThrownBy(() -> userListItemService.addItems(lucasId, listId,
                new UserListItemBulkCreationDTO(List.of(contentRefCreation("550")))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot also contain content items");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[addItems] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnAddItems() {
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.addItems(lucasId, listId,
                new UserListItemBulkCreationDTO(List.of(contentRefCreation("550")))))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[addItems] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnAddItems() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.addItems(marinaId, listId,
                new UserListItemBulkCreationDTO(List.of(contentRefCreation("550")))))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(contentService);
    }

    // ---------- updateItem ----------

    @Test
    @DisplayName("[updateItem] Should Change Description - When A Different Value Is Provided")
    void shouldChangeDescriptionWhenADifferentValueIsProvided() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        item.setDescription("Old description");
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.updateItem(lucasId, listId, item.getId(), new UserListItemPatchDTO(null, "New description"));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getDescription()).isEqualTo("New description");
        verify(userListItemRepository, never()).parkPositionsInRange(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("[updateItem] Should Not Save - When Same Description Value Is Provided")
    void shouldNotSaveWhenSameDescriptionValueIsProvided() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        item.setDescription("Same description");
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        userListItemService.updateItem(lucasId, listId, item.getId(), new UserListItemPatchDTO(null, "Same description"));

        verify(userListItemRepository, never()).save(any());
        verify(userListItemRepository, never()).flush();
    }

    @Test
    @DisplayName("[updateItem] Should Not Change Anything - When Both Position And Description Are Null")
    void shouldNotChangeAnythingWhenBothPositionAndDescriptionAreNull() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        userListItemService.updateItem(lucasId, listId, item.getId(), new UserListItemPatchDTO(null, null));

        verify(userListItemRepository, never()).save(any());
        verify(userListItemRepository, never()).flush();
    }

    @Test
    @DisplayName("[updateItem] Should Park Item Then Bulk Shift Others Forward - When Moving To An Earlier Position")
    void shouldParkItemThenBulkShiftOthersForwardWhenMovingToAnEarlierPosition() {
        UserListItem d = buildContentItem(scifi, buildContent("4", ContentType.MOVIE), 4);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(userListItemRepository.countByUserListId(listId)).thenReturn(4L);
        List<Integer> savedPositions = new ArrayList<>();
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> {
            UserListItem arg = invocation.getArgument(0);
            savedPositions.add(arg.getPosition());
            return arg;
        });

        userListItemService.updateItem(lucasId, listId, d.getId(), new UserListItemPatchDTO(2, null));

        verify(userListItemRepository, times(2)).save(any(UserListItem.class));
        assertThat(savedPositions).containsExactly(5, 2);
        assertThat(d.getPosition()).isEqualTo(2);
        verify(userListItemRepository).parkPositionsInRange(
                listId, 2, 3, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).settleParkedPositions(
                listId, UserListItemServiceImpl.POSITION_PARK_OFFSET, 1);
        verify(userListItemRepository, times(2)).flush();
    }

    @Test
    @DisplayName("[updateItem] Should Park Item Then Bulk Shift Others Backward - When Moving To A Later Position")
    void shouldParkItemThenBulkShiftOthersBackwardWhenMovingToALaterPosition() {
        UserListItem a = buildContentItem(scifi, buildContent("1", ContentType.MOVIE), 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(userListItemRepository.countByUserListId(listId)).thenReturn(4L);
        List<Integer> savedPositions = new ArrayList<>();
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> {
            UserListItem arg = invocation.getArgument(0);
            savedPositions.add(arg.getPosition());
            return arg;
        });

        userListItemService.updateItem(lucasId, listId, a.getId(), new UserListItemPatchDTO(3, null));

        verify(userListItemRepository, times(2)).save(any(UserListItem.class));
        assertThat(savedPositions).containsExactly(5, 3);
        assertThat(a.getPosition()).isEqualTo(3);
        verify(userListItemRepository).parkPositionsInRange(
                listId, 2, 3, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).settleParkedPositions(
                listId, UserListItemServiceImpl.POSITION_PARK_OFFSET, -1);
        verify(userListItemRepository, times(2)).flush();
    }

    @Test
    @DisplayName("[updateItem] Should Not Save - When New Position Equals Current Position")
    void shouldNotSaveWhenNewPositionEqualsCurrentPosition() {
        UserListItem item = buildContentItem(scifi, fightClub, 2);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        userListItemService.updateItem(lucasId, listId, item.getId(), new UserListItemPatchDTO(2, null));

        verify(userListItemRepository, never()).save(any());
        verify(userListItemRepository, never()).flush();
        verify(userListItemRepository, never()).countByUserListId(any());
    }

    @Test
    @DisplayName("[updateItem] Should Update Both Position And Description - When Both Are Provided")
    void shouldUpdateBothPositionAndDescriptionWhenBothAreProvided() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(userListItemRepository.countByUserListId(listId)).thenReturn(2L);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.updateItem(lucasId, listId, item.getId(), new UserListItemPatchDTO(2, "Updated"));

        assertThat(item.getDescription()).isEqualTo("Updated");
        assertThat(item.getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("[updateItem] Should Throw BadRequestException - When New Position Is Greater Than The Current Item Count")
    void shouldThrowBadRequestExceptionWhenNewPositionIsGreaterThanTheCurrentItemCount() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(userListItemRepository.countByUserListId(listId)).thenReturn(1L);

        assertThatThrownBy(() -> userListItemService.updateItem(
                lucasId, listId, item.getId(), new UserListItemPatchDTO(2, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("position cannot be greater than 1, the last position in the list");

        verify(userListItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateItem] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnUpdate() {
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.updateItem(
                lucasId, listId, UUID.randomUUID(), new UserListItemPatchDTO(1, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(userListItemRepository);
    }

    @Test
    @DisplayName("[updateItem] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnUpdate() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.updateItem(
                marinaId, listId, UUID.randomUUID(), new UserListItemPatchDTO(1, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(userListItemRepository);
    }

    @Test
    @DisplayName("[updateItem] Should Throw NotFoundException - When Item Does Not Exist")
    void shouldThrowNotFoundExceptionWhenItemDoesNotExistOnUpdate() {
        UUID itemId = UUID.randomUUID();
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.updateItem(
                lucasId, listId, itemId, new UserListItemPatchDTO(1, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List item not found");
    }

    @Test
    @DisplayName("[updateItem] Should Throw NotFoundException - When Item Belongs To A Different List")
    void shouldThrowNotFoundExceptionWhenItemBelongsToADifferentListOnUpdate() {
        UserList anotherList = buildUserList(UUID.randomUUID(), lucas, "Another list", UserListVisibility.PUBLIC);
        UserListItem item = buildContentItem(anotherList, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> userListItemService.updateItem(
                lucasId, listId, item.getId(), new UserListItemPatchDTO(1, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List item not found");
    }

    @Test
    @DisplayName("[updateItem] Should Throw ConflictException - When A Concurrent Update Causes A Data Integrity Violation")
    void shouldThrowConflictExceptionWhenAConcurrentUpdateCausesADataIntegrityViolationOnUpdate() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(userListItemRepository.countByUserListId(listId)).thenReturn(2L);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent modification"));

        assertThatThrownBy(() -> userListItemService.updateItem(
                lucasId, listId, item.getId(), new UserListItemPatchDTO(2, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("List item could not be reordered due to a concurrent update");
    }

    @Test
    @DisplayName("[removeItem] Should Delete And Park/Settle Positions - When No Remaining Item Has A Higher Position")
    void shouldDeleteAndNotShiftWhenNoRemainingItemHasAHigherPosition() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        userListItemService.removeItem(lucasId, listId, item.getId());

        verify(userListItemRepository).delete(item);
        verify(userListItemRepository).parkPositionsInRange(
                listId, 2, Integer.MAX_VALUE, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).settleParkedPositions(
                listId, UserListItemServiceImpl.POSITION_PARK_OFFSET, -1);
        verify(userListItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("[removeItem] Should Park And Settle Remaining Positions - When Item Removed Is In The Middle")
    void shouldShiftRemainingPositionsDownWhenItemRemovedIsInTheMiddle() {
        UserListItem removed = buildContentItem(scifi, fightClub, 2);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(removed.getId())).thenReturn(Optional.of(removed));

        userListItemService.removeItem(lucasId, listId, removed.getId());

        verify(userListItemRepository).delete(removed);
        verify(userListItemRepository).parkPositionsInRange(
                listId, 3, Integer.MAX_VALUE, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).settleParkedPositions(
                listId, UserListItemServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    @Test
    @DisplayName("[removeItem] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnRemove() {
        when(userListRepository.findById(listId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.removeItem(lucasId, listId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(userListItemRepository);
    }

    @Test
    @DisplayName("[removeItem] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnRemove() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));

        assertThatThrownBy(() -> userListItemService.removeItem(marinaId, listId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verifyNoInteractions(userListItemRepository);
    }

    @Test
    @DisplayName("[removeItem] Should Throw NotFoundException - When Item Does Not Exist")
    void shouldThrowNotFoundExceptionWhenItemDoesNotExist() {
        UUID itemId = UUID.randomUUID();
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(itemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListItemService.removeItem(lucasId, listId, itemId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List item not found");
    }

    @Test
    @DisplayName("[removeItem] Should Throw NotFoundException - When Item Belongs To A Different List")
    void shouldThrowNotFoundExceptionWhenItemBelongsToADifferentList() {
        UserList anotherList = buildUserList(UUID.randomUUID(), lucas, "Another list", UserListVisibility.PUBLIC);
        UserListItem item = buildContentItem(anotherList, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> userListItemService.removeItem(lucasId, listId, item.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List item not found");

        verify(userListItemRepository, never()).delete(any());
    }

    // ---------- removeItemsReferencingChildList ----------

    @Test
    @DisplayName("[removeItemsReferencingChildList] Should Delete The Referencing Item And Close The Gap - When One Parent List References It")
    void shouldDeleteTheReferencingItemAndCloseTheGapWhenOneParentListReferencesIt() {
        UUID nestedListId = UUID.randomUUID();
        UserListItem referencingItem = buildChildListItem(scifi, buildUserList(nestedListId, lucas, "Nested", UserListVisibility.PUBLIC), 2);
        when(userListItemRepository.findByChildListId(nestedListId)).thenReturn(List.of(referencingItem));

        userListItemService.removeItemsReferencingChildList(nestedListId);

        verify(userListItemRepository).delete(referencingItem);
        verify(userListItemRepository).parkPositionsInRange(
                listId, 3, Integer.MAX_VALUE, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).settleParkedPositions(
                listId, UserListItemServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    @Test
    @DisplayName("[removeItemsReferencingChildList] Should Close The Gap In Each Distinct Parent List - When Multiple Lists Reference It")
    void shouldCloseTheGapInEachDistinctParentListWhenMultipleListsReferenceIt() {
        UUID nestedListId = UUID.randomUUID();
        UserList nested = buildUserList(nestedListId, lucas, "Nested", UserListVisibility.PUBLIC);
        UserList marinasList = buildUserList(UUID.randomUUID(), marina, "Marina's list", UserListVisibility.PUBLIC);
        UserListItem itemInScifi = buildChildListItem(scifi, nested, 1);
        UserListItem itemInMarinasList = buildChildListItem(marinasList, nested, 1);
        when(userListItemRepository.findByChildListId(nestedListId))
                .thenReturn(List.of(itemInScifi, itemInMarinasList));

        userListItemService.removeItemsReferencingChildList(nestedListId);

        verify(userListItemRepository).delete(itemInScifi);
        verify(userListItemRepository).delete(itemInMarinasList);
        verify(userListItemRepository).parkPositionsInRange(
                listId, 2, Integer.MAX_VALUE, UserListItemServiceImpl.POSITION_PARK_OFFSET);
        verify(userListItemRepository).parkPositionsInRange(
                marinasList.getId(), 2, Integer.MAX_VALUE, UserListItemServiceImpl.POSITION_PARK_OFFSET);
    }

    @Test
    @DisplayName("[removeItemsReferencingChildList] Should Do Nothing - When No Item References It")
    void shouldDoNothingWhenNoItemReferencesIt() {
        UUID nestedListId = UUID.randomUUID();
        when(userListItemRepository.findByChildListId(nestedListId)).thenReturn(List.of());

        userListItemService.removeItemsReferencingChildList(nestedListId);

        verify(userListItemRepository, never()).delete(any());
        verify(userListItemRepository, never()).parkPositionsInRange(any(), anyInt(), anyInt(), anyInt());
    }

    private void stubContentResolution(Content content, ContentType type) {
        ContentRefDTO contentRefDto = new ContentRefDTO(
                content.getId(), content.getTmdbId(), type, null, null, null, null, null,
                content.getCreatedAt(), content.getUpdatedAt());
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenReturn(contentRefDto);
    }

    private ContentRefCreationDTO contentRefCreation(String tmdbId) {
        return new ContentRefCreationDTO(tmdbId, ContentType.MOVIE, null, null, null, null, null);
    }

    private UserListItemResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        return new UserListItemResponseDTO(UUID.randomUUID(), null, null, 1, null, now, now);
    }

    private UserListItem buildContentItem(UserList userList, Content content, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return UserListItem.builder()
                .id(UUID.randomUUID())
                .userList(userList)
                .content(content)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserListItem buildChildListItem(UserList userList, UserList childList, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return UserListItem.builder()
                .id(UUID.randomUUID())
                .userList(userList)
                .childList(childList)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO buildPreviewDto(UserList list) {
        return new com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO(
                list.getId(), null, list.getName(), list.getVisibility());
    }

    private UserList buildUserList(UUID id, User owner, String name, UserListVisibility visibility) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .id(id)
                .user(owner)
                .name(name)
                .visibility(visibility)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ContentRefDTO buildContentRefDto(Content content) {
        return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null, null, null, content.getCreatedAt(), content.getUpdatedAt());
    }

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .id(UUID.randomUUID())
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DataIntegrityViolationException buildDataIntegrityViolationException(String constraintName) {
        org.hibernate.exception.ConstraintViolationException cve =
                new org.hibernate.exception.ConstraintViolationException("constraint violated", null, constraintName);
        return new DataIntegrityViolationException("db error", cve);
    }

    private UserListItemRepository.UserListCount buildUserListCount(UUID userListId, long count) {
        return new UserListItemRepository.UserListCount() {
            @Override
            public UUID getUserListId() {
                return userListId;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}