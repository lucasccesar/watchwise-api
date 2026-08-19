package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.mapper.UserListMapper;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserListServiceImplTest {

    @Mock
    private UserListRepository userListRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserListMapper userListMapper;

    @InjectMocks
    private UserListServiceImpl userListService;

    @Captor
    private ArgumentCaptor<UserList> listCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;

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
    }

    // ---------- getUserLists ----------

    @Test
    @DisplayName("[getUserLists] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwner() {
        UserList list = buildList(lucas, "My list", null, true);
        UserListResponseDTO dto = buildResponseDto(list);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(list)));
        when(userListMapper.userListToResponseDto(list)).thenReturn(dto);

        Page<UserListResponseDTO> result = userListService.getUserLists(lucasId, lucasId, 1, 10);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getUserLists] Should Return Empty Page - When User Has No Lists")
    void shouldReturnEmptyPageWhenUserHasNoLists() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        Page<UserListResponseDTO> result = userListService.getUserLists(lucasId, lucasId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(userListMapper, never()).userListToResponseDto(any());
    }

    @Test
    @DisplayName("[getUserLists] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(userListRepository);
    }

    @Test
    @DisplayName("[getUserLists] Should Query All Lists - When Viewer Is The Owner")
    void shouldQueryAllListsWhenViewerIsTheOwner() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(lucasId, lucasId, 1, 10);

        verify(userListRepository).findByUserId(eq(lucasId), any(PageRequest.class));
        verify(userListRepository, never()).findByUserIdAndIsPublicTrue(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Query Only Public Lists - When Viewer Is A Different User")
    void shouldQueryOnlyPublicListsWhenViewerIsADifferentUser() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserIdAndIsPublicTrue(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        userListService.getUserLists(marinaId, lucasId, 1, 10);

        verify(userListRepository).findByUserIdAndIsPublicTrue(eq(lucasId), any(PageRequest.class));
        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, null, 10);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(UserListServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 0, 10);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(UserListServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 3, 10);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, null);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(UserListServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Default Page Size - When Page Size Exceeds Limit")
    void shouldUseDefaultPageSizeWhenPageSizeExceedsLimit() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, 1001);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(UserListServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, 25);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getUserLists] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyOwnListsPage();

        userListService.getUserLists(lucasId, lucasId, 1, 1000);

        verify(userListRepository).findByUserId(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(userListRepository, never()).findByUserId(any(), any());
    }

    @Test
    @DisplayName("[getUserLists] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> userListService.getUserLists(lucasId, lucasId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(userListRepository, never()).findByUserId(any(), any());
    }

    // ---------- createUserList ----------

    @Test
    @DisplayName("[createUserList] Should Save New List With Provided Fields")
    void shouldSaveNewListWithProvidedFields() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class))).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        UserListResponseDTO result = userListService.createUserList(
                lucasId, new UserListCreationDTO("Best sci-fi of the 90s", "A curated list", false));

        assertThat(result.name()).isEqualTo("Best sci-fi of the 90s");
        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getUser()).isEqualTo(lucas);
        assertThat(listCaptor.getValue().getName()).isEqualTo("Best sci-fi of the 90s");
        assertThat(listCaptor.getValue().getDescription()).isEqualTo("A curated list");
        assertThat(listCaptor.getValue().getIsPublic()).isFalse();
        assertThat(listCaptor.getValue().getCreatedAt()).isNotNull();
        assertThat(listCaptor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("[createUserList] Should Default IsPublic To True - When Omitted")
    void shouldDefaultIsPublicToTrueWhenOmitted() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class))).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.createUserList(lucasId, new UserListCreationDTO("My list", null, null));

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getIsPublic()).isTrue();
    }

    @Test
    @DisplayName("[createUserList] Should Persist A Null Description - When Omitted")
    void shouldPersistANullDescriptionWhenOmitted() {
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class))).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.createUserList(lucasId, new UserListCreationDTO("My list", null, true));

        verify(userListRepository).save(listCaptor.capture());
        assertThat(listCaptor.getValue().getDescription()).isNull();
    }

    // ---------- updateUserList ----------

    @Test
    @DisplayName("[updateUserList] Should Update Name, Description And IsPublic - When Provided")
    void shouldUpdateNameDescriptionAndIsPublicWhenProvided() {
        UserList existing = buildList(lucas, "Old name", "Old description", true);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class))).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        UserListResponseDTO result = userListService.updateUserList(
                lucasId, existing.getId(), new UserListCreationDTO("New name", "New description", false));

        assertThat(result.name()).isEqualTo("New name");
        assertThat(existing.getName()).isEqualTo("New name");
        assertThat(existing.getDescription()).isEqualTo("New description");
        assertThat(existing.getIsPublic()).isFalse();
    }

    @Test
    @DisplayName("[updateUserList] Should Default IsPublic To True - When Omitted")
    void shouldDefaultIsPublicToTrueWhenOmittedOnUpdate() {
        UserList existing = buildList(lucas, "Old name", null, false);
        when(userListRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userListRepository.save(any(UserList.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userListMapper.userListToResponseDto(any(UserList.class))).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        userListService.updateUserList(lucasId, existing.getId(), new UserListCreationDTO("Old name", null, null));

        assertThat(existing.getIsPublic()).isTrue();
    }

    @Test
    @DisplayName("[updateUserList] Should Throw NotFoundException - When List Does Not Exist")
    void shouldThrowNotFoundExceptionWhenListDoesNotExistOnUpdate() {
        UUID missingId = UUID.randomUUID();
        when(userListRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, missingId, new UserListCreationDTO("Name", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateUserList] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnUpdate() {
        User marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        UserList marinasList = buildList(marina, "Marina's list", null, true);
        when(userListRepository.findById(marinasList.getId())).thenReturn(Optional.of(marinasList));

        assertThatThrownBy(() -> userListService.updateUserList(
                lucasId, marinasList.getId(), new UserListCreationDTO("Name", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).save(any());
    }

    // ---------- deleteUserList ----------

    @Test
    @DisplayName("[deleteUserList] Should Delete The List - When Owned By The User")
    void shouldDeleteTheListWhenOwnedByTheUser() {
        UserList list = buildList(lucas, "My list", null, true);
        when(userListRepository.findById(list.getId())).thenReturn(Optional.of(list));

        userListService.deleteUserList(lucasId, list.getId());

        verify(userListRepository).delete(list);
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
    }

    @Test
    @DisplayName("[deleteUserList] Should Throw NotFoundException - When List Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenListBelongsToADifferentUserOnDelete() {
        User marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        UserList marinasList = buildList(marina, "Marina's list", null, true);
        when(userListRepository.findById(marinasList.getId())).thenReturn(Optional.of(marinasList));

        assertThatThrownBy(() -> userListService.deleteUserList(lucasId, marinasList.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("List not found");

        verify(userListRepository, never()).delete(any());
    }

    // ---------- helpers ----------

    private void stubEmptyOwnListsPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(userListRepository.findByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());
    }

    private UserList buildList(User user, String name, String description, boolean isPublic) {
        LocalDateTime now = LocalDateTime.now();
        return UserList.builder()
                .id(UUID.randomUUID())
                .user(user)
                .name(name)
                .description(description)
                .isPublic(isPublic)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private UserListResponseDTO buildResponseDto(UserList list) {
        User owner = list.getUser();
        UserPreviewDTO ownerPreview = new UserPreviewDTO(owner.getId(), owner.getUsername(), owner.getProfilePicture(), owner.getIsProfilePublic());
        return new UserListResponseDTO(
                list.getId(), ownerPreview, list.getName(), list.getDescription(),
                list.getIsPublic(), 0.0, list.getCreatedAt(), list.getUpdatedAt());
    }
}