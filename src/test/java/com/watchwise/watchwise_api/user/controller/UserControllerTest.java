package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.AttemptLockout;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.user.dto.DeleteAccountDTO;
import com.watchwise.watchwise_api.user.dto.PatchUserDTO;
import com.watchwise.watchwise_api.user.dto.PublicUserDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private CookieUtil cookieUtil;

    @Mock
    private AttemptLockout attemptLockout;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private UserController userController;

    private UUID currentUserId;
    private User currentUser;
    private UserResponseDTO userResponseDTO;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        currentUser = User.builder()
                .id(currentUserId)
                .username("JohnDoe")
                .email("john.doe@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
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
    @DisplayName("[getUsersByUsername] Should Return Page Envelope With Content And Metadata - When Service Returns Results")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenServiceReturnsResults() {
        UserPreviewDTO previewDTO = new UserPreviewDTO(
                UUID.randomUUID(),
                "JaneDoe",
                "https://picture.com/pic.png",
                true
        );
        Page<UserPreviewDTO> page = new PageImpl<>(List.of(previewDTO), PageRequest.of(0, 10), 1);
        when(userService.getUsersByUsername("jane", 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<UserPreviewDTO>> result = userController.getUsersByUsername("jane", 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().content()).containsExactly(previewDTO);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().size()).isEqualTo(10);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().totalPages()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Empty Content - When Service Returns No Results")
    void shouldReturnEmptyContentWhenServiceReturnsNoResults() {
        when(userService.getUsersByUsername("nobody", null, null)).thenReturn(Page.empty());

        ResponseEntity<PageResponseDTO<UserPreviewDTO>> result = userController.getUsersByUsername("nobody", null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).isEmpty();
        assertThat(result.getBody().totalElements()).isZero();
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Check Rate Limit Before Querying - When Called")
    void shouldCheckRateLimitBeforeQueryingWhenGetUsersByUsernameCalled() {
        when(userService.getUsersByUsername(any(), any(), any())).thenReturn(Page.empty());

        userController.getUsersByUsername("jane", null, null);

        InOrder order = inOrder(requestThrottler, userService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(userService).getUsersByUsername("jane", null, null);
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Throw TooManyRequestsException And Not Query - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotQueryWhenGetUsersByUsernameRateLimited() {
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(any(), anyInt(), any());

        assertThatThrownBy(() -> userController.getUsersByUsername("jane", null, null))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userService, never()).getUsersByUsername(any(), any(), any());
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
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated bio", null, null, null);
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, false));
        when(userService.updateUser(currentUser, patchUserDTO)).thenReturn(userResponseDTO);

        ResponseEntity<UserResponseDTO> result = userController.updateCurrentUser(patchUserDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Resolve Id From The Security Context - When Called")
    void shouldResolveIdFromTheSecurityContextWhenUpdateCalled() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated bio", null, null, null);
        when(userService.checkCredentialChanges(any(UUID.class), any(PatchUserDTO.class)))
                .thenReturn(new UserService.CredentialCheck(currentUser, false));
        when(userService.updateUser(any(User.class), any(PatchUserDTO.class))).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        verify(userService).checkCredentialChanges(currentUserId, patchUserDTO);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Not Interact With AttemptLockout - When Patch Does Not Touch Password Or Email")
    void shouldNotInteractWithAttemptLockoutWhenPatchDoesNotTouchPasswordOrEmail() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, null, "Updated bio", null, null, null);
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, false));
        when(userService.updateUser(currentUser, patchUserDTO)).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        verifyNoInteractions(attemptLockout);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Not Interact With AttemptLockout - When Email Is Provided But Unchanged")
    void shouldNotInteractWithAttemptLockoutWhenEmailIsProvidedButUnchanged() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "john.doe@email.com", null, null, null, null, null);
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, false));
        when(userService.updateUser(currentUser, patchUserDTO)).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        verifyNoInteractions(attemptLockout);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Check Lockout Before Updating - When Credentials Will Change")
    void shouldCheckLockoutBeforeUpdatingWhenCredentialsWillChange() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "CurrentPass1");
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, true));
        when(userService.updateUser(currentUser, patchUserDTO)).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        InOrder order = inOrder(attemptLockout, userService);
        order.verify(attemptLockout).checkAllowed(any());
        order.verify(userService).updateUser(currentUser, patchUserDTO);
        verify(attemptLockout).recordSuccess(any());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Build Lockout Key From Action And UserId - When Credentials Will Change")
    void shouldBuildLockoutKeyFromActionAndUserIdWhenCredentialsWillChange() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "CurrentPass1");
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, true));
        when(userService.updateUser(currentUser, patchUserDTO)).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        verify(attemptLockout).checkAllowed("patch-account|" + currentUserId);
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Record Failure And Rethrow - When Credentials Will Change And Service Throws UnauthorizedException")
    void shouldRecordFailureAndRethrowWhenCredentialsWillChangeAndServiceThrowsUnauthorizedException() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "WrongCurrentPass1");
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, true));
        when(userService.updateUser(currentUser, patchUserDTO)).thenThrow(new UnauthorizedException("Invalid password"));

        assertThatThrownBy(() -> userController.updateCurrentUser(patchUserDTO))
                .isInstanceOf(UnauthorizedException.class);

        verify(attemptLockout).recordFailure(any(), anyInt(), any(), any());
        verify(attemptLockout, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Not Record Success - When Email Is Provided But Unchanged")
    void shouldNotRecordSuccessWhenEmailIsProvidedButUnchanged() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, "john.doe@email.com", null, null, null, null, null);
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, false));
        when(userService.updateUser(currentUser, patchUserDTO)).thenReturn(userResponseDTO);

        userController.updateCurrentUser(patchUserDTO);

        verify(attemptLockout, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Throw TooManyRequestsException And Not Call Service - When Credentials Will Change And Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotCallServiceWhenCredentialsWillChangeAndRateLimited() {
        PatchUserDTO patchUserDTO = new PatchUserDTO(null, null, "NewPassword123", null, null, null, "CurrentPass1");
        when(userService.checkCredentialChanges(currentUserId, patchUserDTO))
                .thenReturn(new UserService.CredentialCheck(currentUser, true));
        doThrow(new TooManyRequestsException("Too many attempts. Try again later."))
                .when(attemptLockout).checkAllowed(any());

        assertThatThrownBy(() -> userController.updateCurrentUser(patchUserDTO))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userService, never()).updateUser(any(), any());
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

    @Test
    @DisplayName("[getUserById] Should Check Rate Limit Before Querying - When Called")
    void shouldCheckRateLimitBeforeQueryingWhenGetUserByIdCalled() {
        UUID id = UUID.randomUUID();
        when(userService.getUserById(id)).thenReturn(mock(PublicUserDTO.class));

        userController.getUserById(id);

        InOrder order = inOrder(requestThrottler, userService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(userService).getUserById(id);
    }

    @Test
    @DisplayName("[getUserById] Should Throw TooManyRequestsException And Not Query - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotQueryWhenGetUserByIdRateLimited() {
        UUID id = UUID.randomUUID();
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(any(), anyInt(), any());

        assertThatThrownBy(() -> userController.getUserById(id))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userService, never()).getUserById(any());
    }

    @Test
    @DisplayName("[getUsersByUsername][getUserById] Should Share The Same Throttle Key - When Called By The Same User")
    void shouldShareTheSameThrottleKeyWhenCalledByTheSameUser() {
        UUID targetId = UUID.randomUUID();
        when(userService.getUsersByUsername(any(), any(), any())).thenReturn(Page.empty());
        when(userService.getUserById(targetId)).thenReturn(mock(PublicUserDTO.class));

        userController.getUsersByUsername("jane", null, null);
        userController.getUserById(targetId);

        verify(requestThrottler, times(2)).checkAllowed(eq("profile-scan|" + currentUserId), anyInt(), any());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return NoContent - When Deletion Succeeds")
    void shouldReturnNoContentWhenDeletionSucceeds() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        HttpServletResponse response = mock(HttpServletResponse.class);

        ResponseEntity<Void> result = userController.deleteCurrentUser(deleteAccountDTO, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Resolve Id From The Security Context - When Called")
    void shouldResolveIdFromTheSecurityContextWhenDeleteCalled() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        HttpServletResponse response = mock(HttpServletResponse.class);

        userController.deleteCurrentUser(deleteAccountDTO, response);

        verify(userService).deleteAccount(currentUserId, deleteAccountDTO);
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Clear Access, Refresh And Csrf Cookies - When Deletion Succeeds")
    void shouldClearAccessRefreshAndCsrfCookiesWhenDeletionSucceeds() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        HttpServletResponse response = mock(HttpServletResponse.class);
        ResponseCookie clearedCookie = ResponseCookie.from("cleared", "").build();
        when(cookieUtil.getRefreshTokenPath()).thenReturn("/api/v1/auth/refresh");
        when(cookieUtil.getCsrfTokenPath()).thenReturn("/api/v1");
        when(cookieUtil.clearCookie(anyString(), anyString())).thenReturn(clearedCookie);

        userController.deleteCurrentUser(deleteAccountDTO, response);

        verify(cookieUtil).clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/");
        verify(cookieUtil).clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, "/api/v1/auth/refresh");
        verify(cookieUtil).clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/api/v1");
        verify(cookieUtil, times(3)).addCookie(eq(response), eq(clearedCookie));
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Check Lockout Before Deleting - When Called")
    void shouldCheckLockoutBeforeDeletingWhenCalled() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        HttpServletResponse response = mock(HttpServletResponse.class);

        userController.deleteCurrentUser(deleteAccountDTO, response);

        InOrder order = inOrder(attemptLockout, userService);
        order.verify(attemptLockout).checkAllowed(any());
        order.verify(userService).deleteAccount(currentUserId, deleteAccountDTO);
        verify(attemptLockout).recordSuccess(any());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Build Lockout Key From Action And UserId - When Called")
    void shouldBuildLockoutKeyFromActionAndUserIdWhenDeleteCalled() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        HttpServletResponse response = mock(HttpServletResponse.class);

        userController.deleteCurrentUser(deleteAccountDTO, response);

        verify(attemptLockout).checkAllowed("delete-account|" + currentUserId);
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Record Failure And Rethrow - When Service Throws UnauthorizedException")
    void shouldRecordFailureAndRethrowWhenDeleteServiceThrowsUnauthorizedException() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("WrongPassword123");
        HttpServletResponse response = mock(HttpServletResponse.class);
        doThrow(new UnauthorizedException("Invalid password"))
                .when(userService).deleteAccount(currentUserId, deleteAccountDTO);

        assertThatThrownBy(() -> userController.deleteCurrentUser(deleteAccountDTO, response))
                .isInstanceOf(UnauthorizedException.class);

        verify(attemptLockout).recordFailure(any(), anyInt(), any(), any());
        verify(attemptLockout, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Throw TooManyRequestsException And Not Call Service - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotCallServiceWhenDeleteRateLimited() {
        DeleteAccountDTO deleteAccountDTO = new DeleteAccountDTO("Password123");
        HttpServletResponse response = mock(HttpServletResponse.class);
        doThrow(new TooManyRequestsException("Too many attempts. Try again later."))
                .when(attemptLockout).checkAllowed(any());

        assertThatThrownBy(() -> userController.deleteCurrentUser(deleteAccountDTO, response))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userService, never()).deleteAccount(any(), any());
    }
}
