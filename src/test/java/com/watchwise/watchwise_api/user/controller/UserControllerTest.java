package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
