package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    private UUID currentUserId;
    private UserResponseDTO userResponseDTO;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        userResponseDTO = new UserResponseDTO(
                currentUserId,
                "JohnDoe",
                "john.doe@email.com",
                "Some description",
                "https://picture.com/pic.png",
                true,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return List Of UserPreviewDTO - When Service Returns Results")
    void shouldReturnListOfUserPreviewDtoWhenServiceReturnsResults() {
        UserPreviewDTO previewDTO = new UserPreviewDTO(
                UUID.randomUUID(),
                "JaneDoe",
                "https://picture.com/pic.png",
                true
        );
        Page<UserPreviewDTO> page = new PageImpl<>(List.of(previewDTO));
        when(userService.getUsersByUsername("jane", 1, 10, null)).thenReturn(page);

        ResponseEntity<List<UserPreviewDTO>> result = userController.getUsersByUsername("jane", 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(previewDTO);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Empty List - When Service Returns No Results")
    void shouldReturnEmptyListWhenServiceReturnsNoResults() {
        when(userService.getUsersByUsername("nobody", null, null, null)).thenReturn(Page.empty());

        ResponseEntity<List<UserPreviewDTO>> result = userController.getUsersByUsername("nobody", null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEmpty();
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Always Pass Null IsProfilePublic Filter To Service - When Called")
    void shouldAlwaysPassNullIsProfilePublicFilterToServiceWhenCalled() {
        when(userService.getUsersByUsername(eq("jane"), any(), any(), isNull())).thenReturn(Page.empty());

        userController.getUsersByUsername("jane", 2, 5);

        verify(userService).getUsersByUsername("jane", 2, 5, null);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO Of The Authenticated User - When Called")
    void shouldReturnUserResponseDtoOfTheAuthenticatedUserWhenCalled() {
        when(userService.getCurrentUser(currentUserId)).thenReturn(userResponseDTO);

        ResponseEntity<UserResponseDTO> result = userController.getCurrentUser();

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Resolve Id From The Security Context - When Called")
    void shouldResolveIdFromTheSecurityContextWhenCalled() {
        when(userService.getCurrentUser(currentUserId)).thenReturn(userResponseDTO);

        userController.getCurrentUser();

        verify(userService).getCurrentUser(currentUserId);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return UserResponseDTO Of The Authenticated User - When Called")
    void shouldReturnUserResponseDtoOfTheAuthenticatedUserWhenUpdateCalled() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated bio", null, null);
        when(userService.updateUser(currentUserId, patchUserDTO)).thenReturn(userResponseDTO);

        ResponseEntity<UserResponseDTO> result = userController.updateCurrentUser(patchUserDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Resolve Id From The Security Context - When Called")
    void shouldResolveIdFromTheSecurityContextWhenUpdateCalled() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated bio", null, null);
        when(userService.updateUser(any(UUID.class), any(PatchUserDTO.class))).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        verify(userService).updateUser(currentUserId, patchUserDTO);
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserDTO - When Id Exists")
    void shouldReturnPublicUserDtoWhenIdExists() {
        UUID id = UUID.randomUUID();
        PublicUserDTO publicUserDTO = new PublicUserDTO(
                id,
                "JaneDoe",
                "Some description",
                "https://picture.com/pic.png",
                true,
                LocalDateTime.now()
        );
        when(userService.getUserById(id)).thenReturn(publicUserDTO);

        ResponseEntity<PublicUserDTO> result = userController.getUserById(id);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(publicUserDTO);
        verify(userService).getUserById(id);
    }
}
