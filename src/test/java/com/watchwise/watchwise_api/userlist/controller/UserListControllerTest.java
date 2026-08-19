package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListResponseDTO;
import com.watchwise.watchwise_api.userlist.service.UserListService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserListControllerTest {

    @Mock
    private UserListService userListService;

    @InjectMocks
    private UserListController userListController;

    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getUserLists] Should Return Ok With The Service Result Wrapped In A Page Envelope - When Called")
    void shouldReturnOkWithTheServiceResultWrappedInAPageEnvelopeWhenGettingUserLists() {
        UUID targetUserId = UUID.randomUUID();
        UserListResponseDTO dto = buildResponseDto();
        Page<UserListResponseDTO> page = new PageImpl<>(List.of(dto));
        when(userListService.getUserLists(currentUserId, targetUserId, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<UserListResponseDTO>> result = userListController.getUserLists(targetUserId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getUserLists] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingUserLists() {
        UUID targetUserId = UUID.randomUUID();
        when(userListService.getUserLists(currentUserId, targetUserId, 1, 10)).thenReturn(Page.empty());

        userListController.getUserLists(targetUserId, 1, 10);

        verify(userListService).getUserLists(currentUserId, targetUserId, 1, 10);
    }

    @Test
    @DisplayName("[createUserList] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenCreatingUserList() {
        UserListCreationDTO creationDTO = new UserListCreationDTO("Best sci-fi of the 90s", null, true);
        UserListResponseDTO dto = buildResponseDto();
        when(userListService.createUserList(currentUserId, creationDTO)).thenReturn(dto);

        ResponseEntity<UserListResponseDTO> result = userListController.createUserList(creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[createUserList] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCreatingUserList() {
        UserListCreationDTO creationDTO = new UserListCreationDTO("My list", null, null);
        when(userListService.createUserList(currentUserId, creationDTO)).thenReturn(buildResponseDto());

        userListController.createUserList(creationDTO);

        verify(userListService).createUserList(currentUserId, creationDTO);
    }

    @Test
    @DisplayName("[updateUserList] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenUpdatingUserList() {
        UUID listId = UUID.randomUUID();
        UserListCreationDTO updateDTO = new UserListCreationDTO("New name", null, false);
        UserListResponseDTO dto = buildResponseDto();
        when(userListService.updateUserList(currentUserId, listId, updateDTO)).thenReturn(dto);

        ResponseEntity<UserListResponseDTO> result = userListController.updateUserList(listId, updateDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[updateUserList] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenUpdatingUserList() {
        UUID listId = UUID.randomUUID();
        UserListCreationDTO updateDTO = new UserListCreationDTO("New name", null, null);
        when(userListService.updateUserList(currentUserId, listId, updateDTO)).thenReturn(buildResponseDto());

        userListController.updateUserList(listId, updateDTO);

        verify(userListService).updateUserList(currentUserId, listId, updateDTO);
    }

    @Test
    @DisplayName("[deleteUserList] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenDeletingUserList() {
        UUID listId = UUID.randomUUID();

        ResponseEntity<Void> result = userListController.deleteUserList(listId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[deleteUserList] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenDeletingUserList() {
        UUID listId = UUID.randomUUID();

        userListController.deleteUserList(listId);

        verify(userListService).deleteUserList(currentUserId, listId);
    }

    private UserListResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        UserPreviewDTO owner = new UserPreviewDTO(UUID.randomUUID(), "lucas", "https://example.com/photo.png", true);
        return new UserListResponseDTO(UUID.randomUUID(), owner, "My list", null, true, 0.0, now, now);
    }
}