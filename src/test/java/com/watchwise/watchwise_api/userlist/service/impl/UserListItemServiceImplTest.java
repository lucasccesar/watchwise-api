package com.watchwise.watchwise_api.userlist.service.impl;

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

    // ---------- getPreviewItems ----------

    @Test
    @DisplayName("[getPreviewItems] Should Return Mapped Content Refs Ordered By Position - When List Has Content Items")
    void shouldReturnMappedContentRefsOrderedByPositionWhenListHasContentItems() {
        UserListItem item1 = buildContentItem(scifi, fightClub, 1);
        ContentRefDTO contentRef1 = buildContentRefDto(fightClub);
        when(userListItemRepository.findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc(listId))
                .thenReturn(List.of(item1));
        when(userListItemMapper.userListItemToResponseDto(item1))
                .thenReturn(new UserListItemResponseDTO(item1.getId(), contentRef1, null, 1, null, LocalDateTime.now(), LocalDateTime.now()));

        List<ContentRefDTO> result = userListItemService.getPreviewItems(listId);

        assertThat(result).containsExactly(contentRef1);
    }

    @Test
    @DisplayName("[getPreviewItems] Should Return Empty List - When List Has No Content Items")
    void shouldReturnEmptyListWhenListHasNoContentItemsForPreview() {
        when(userListItemRepository.findTop5ByUserListIdAndContentIdIsNotNullOrderByPositionAsc(listId))
                .thenReturn(List.of());

        List<ContentRefDTO> result = userListItemService.getPreviewItems(listId);

        assertThat(result).isEmpty();
        verifyNoInteractions(userListItemMapper);
    }

    // ---------- countNestedLists ----------

    @Test
    @DisplayName("[countNestedLists] Should Return The Repository Count - When List Has Nested List Items")
    void shouldReturnTheRepositoryCountWhenListHasNestedListItems() {
        when(userListItemRepository.countByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(3L);

        long result = userListItemService.countNestedLists(listId);

        assertThat(result).isEqualTo(3L);
    }

    @Test
    @DisplayName("[countNestedLists] Should Return Zero - When List Has No Nested List Items")
    void shouldReturnZeroWhenListHasNoNestedListItems() {
        when(userListItemRepository.countByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(0L);

        long result = userListItemService.countNestedLists(listId);

        assertThat(result).isEqualTo(0L);
    }

    @Test
    @DisplayName("[addItem] Should Insert At Position One - When List Is Empty")
    void shouldInsertAtPositionOneWhenListIsEmpty() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        UserListItem existing1 = buildContentItem(scifi, buildContent("1", ContentType.MOVIE), 1);
        UserListItem existing2 = buildContentItem(scifi, buildContent("2", ContentType.MOVIE), 2);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of(existing1, existing2));
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null));

        verify(userListItemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getPosition()).isEqualTo(3);
        verify(userListItemRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[addItem] Should Shift Existing Items Forward - When An Explicit Position Is Given")
    void shouldShiftExistingItemsForwardWhenAnExplicitPositionIsGiven() {
        UserListItem existing1 = buildContentItem(scifi, buildContent("1", ContentType.MOVIE), 1);
        UserListItem existing2 = buildContentItem(scifi, buildContent("2", ContentType.MOVIE), 2);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of(existing1, existing2));
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        List<Map.Entry<UUID, Integer>> saveLog = new ArrayList<>();
        when(userListItemRepository.save(any(UserListItem.class))).thenAnswer(invocation -> {
            UserListItem arg = invocation.getArgument(0);
            saveLog.add(Map.entry(arg.getId() != null ? arg.getId() : UUID.fromString("00000000-0000-0000-0000-000000000000"), arg.getPosition()));
            return arg;
        });

        userListItemService.addItem(lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, 1, null));

        verify(userListItemRepository, times(3)).save(any(UserListItem.class));
        assertThat(saveLog).containsExactly(
                Map.entry(existing2.getId(), 3),
                Map.entry(existing1.getId(), 2),
                Map.entry(UUID.fromString("00000000-0000-0000-0000-000000000000"), 1));
        verify(userListItemRepository, times(3)).flush();
    }

    @Test
    @DisplayName("[addItem] Should Throw BadRequestException - When Position Is Greater Than The Next Free Position")
    void shouldThrowBadRequestExceptionWhenPositionIsGreaterThanTheNextFreePosition() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());

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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(userListItemRepository.save(any(UserListItem.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_some_other_constraint"));

        assertThatThrownBy(() -> userListItemService.addItem(
                lucasId, listId, new UserListItemCreationDTO(contentRefCreation("550"), null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this item into the list");
    }

    @Test
    @DisplayName("[addItem] Should Throw ConflictException With Generic Message - When Cause Is Not ConstraintViolationException")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNotConstraintViolationException() {
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.existsByUserListIdAndChildListIdIsNotNull(listId)).thenReturn(false);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId))
                .thenAnswer(invocation -> List.copyOf(persisted));
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
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());
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

    @Test
    @DisplayName("[removeItem] Should Delete And Not Shift - When No Remaining Item Has A Higher Position")
    void shouldDeleteAndNotShiftWhenNoRemainingItemHasAHigherPosition() {
        UserListItem item = buildContentItem(scifi, fightClub, 1);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of());

        userListItemService.removeItem(lucasId, listId, item.getId());

        verify(userListItemRepository).delete(item);
        verify(userListItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("[removeItem] Should Shift Remaining Positions Down - When Item Removed Is In The Middle")
    void shouldShiftRemainingPositionsDownWhenItemRemovedIsInTheMiddle() {
        UserListItem removed = buildContentItem(scifi, fightClub, 2);
        UserListItem after1 = buildContentItem(scifi, buildContent("2", ContentType.MOVIE), 3);
        UserListItem after2 = buildContentItem(scifi, buildContent("3", ContentType.MOVIE), 4);
        when(userListRepository.findById(listId)).thenReturn(Optional.of(scifi));
        when(userListItemRepository.findById(removed.getId())).thenReturn(Optional.of(removed));
        when(userListItemRepository.findByUserListIdOrderByPositionAsc(listId)).thenReturn(List.of(after1, after2));

        userListItemService.removeItem(lucasId, listId, removed.getId());

        verify(userListItemRepository).delete(removed);
        assertThat(after1.getPosition()).isEqualTo(2);
        assertThat(after2.getPosition()).isEqualTo(3);
        verify(userListItemRepository, times(2)).save(any(UserListItem.class));
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
}