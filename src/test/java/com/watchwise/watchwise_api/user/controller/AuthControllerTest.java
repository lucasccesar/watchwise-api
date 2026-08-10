package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private JwtService jwtService;

    @Mock
    private CookieUtil cookieUtil;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private CsrfAuthenticationStrategy csrfAuthenticationStrategy;

    @InjectMocks
    private AuthController authController;

    private HttpServletRequest request;
    private HttpServletResponse response;
    private UserResponseDTO userResponseDTO;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        userResponseDTO = new UserResponseDTO(
                UUID.randomUUID(),
                "JohnDoe",
                "john.doe@email.com",
                "Some description",
                "https://picture.com/pic.png",
                true,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        );
    }

    private void setAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, java.util.List.of())
        );
    }

    @Test
    @DisplayName("[register] Should Return Created With UserResponseDTO - When Not Authenticated")
    void shouldReturnCreatedWithUserResponseDtoWhenNotAuthenticated() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.saveNewUser(postUserDTO)).thenReturn(userResponseDTO);
        when(jwtService.generateToken(userResponseDTO.id(), userResponseDTO.email(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id(), userResponseDTO.email())).thenReturn("refresh-token");

        ResponseEntity<UserResponseDTO> result = authController.register(postUserDTO, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[register] Should Throw ConflictException And Not Create User - When Already Authenticated")
    void shouldThrowConflictExceptionAndNotCreateUserWhenAlreadyAuthenticated() {
        setAuthenticated();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);

        assertThatThrownBy(() -> authController.register(postUserDTO, request, response))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Already authenticated");

        verify(userService, never()).saveNewUser(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[register] Should Set Access And Refresh Token Cookies - When Registration Succeeds")
    void shouldSetAccessAndRefreshTokenCookiesWhenRegistrationSucceeds() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        ResponseCookie accessCookie = ResponseCookie.from(CookieUtil.ACCESS_TOKEN_COOKIE, "access-token").build();
        ResponseCookie refreshCookie = ResponseCookie.from(CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token").build();
        when(userService.saveNewUser(postUserDTO)).thenReturn(userResponseDTO);
        when(jwtService.generateToken(userResponseDTO.id(), userResponseDTO.email(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id(), userResponseDTO.email())).thenReturn("refresh-token");
        when(cookieUtil.buildAccessTokenCookie("access-token")).thenReturn(accessCookie);
        when(cookieUtil.buildRefreshTokenCookie("refresh-token")).thenReturn(refreshCookie);

        authController.register(postUserDTO, request, response);

        verify(cookieUtil).addCookie(response, accessCookie);
        verify(cookieUtil).addCookie(response, refreshCookie);
    }

    @Test
    @DisplayName("[register] Should Rotate Csrf Token - When Registration Succeeds")
    void shouldRotateCsrfTokenWhenRegistrationSucceeds() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.saveNewUser(postUserDTO)).thenReturn(userResponseDTO);

        authController.register(postUserDTO, request, response);

        verify(csrfAuthenticationStrategy).onAuthentication(eq(null), eq(request), eq(response));
    }

    @Test
    @DisplayName("[login] Should Return Ok With UserResponseDTO - When Not Authenticated")
    void shouldReturnOkWithUserResponseDtoWhenNotAuthenticated() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);

        ResponseEntity<UserResponseDTO> result = authController.login(loginUserDTO, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[login] Should Throw ConflictException And Not Attempt Login - When Already Authenticated")
    void shouldThrowConflictExceptionAndNotAttemptLoginWhenAlreadyAuthenticated() {
        setAuthenticated();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");

        assertThatThrownBy(() -> authController.login(loginUserDTO, request, response))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Already authenticated");

        verify(userService, never()).login(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Set Access And Refresh Token Cookies - When Login Succeeds")
    void shouldSetAccessAndRefreshTokenCookiesWhenLoginSucceeds() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        ResponseCookie accessCookie = ResponseCookie.from(CookieUtil.ACCESS_TOKEN_COOKIE, "access-token").build();
        ResponseCookie refreshCookie = ResponseCookie.from(CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token").build();
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);
        when(jwtService.generateToken(userResponseDTO.id(), userResponseDTO.email(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id(), userResponseDTO.email())).thenReturn("refresh-token");
        when(cookieUtil.buildAccessTokenCookie("access-token")).thenReturn(accessCookie);
        when(cookieUtil.buildRefreshTokenCookie("refresh-token")).thenReturn(refreshCookie);

        authController.login(loginUserDTO, request, response);

        verify(cookieUtil).addCookie(response, accessCookie);
        verify(cookieUtil).addCookie(response, refreshCookie);
    }

    @Test
    @DisplayName("[refresh] Should Return NoContent And Set New Cookies - When Refresh Token Is Valid")
    void shouldReturnNoContentAndSetNewCookiesWhenRefreshTokenIsValid() {
        RefreshedTokens tokens = new RefreshedTokens("new-access-token", "new-refresh-token");
        ResponseCookie accessCookie = ResponseCookie.from(CookieUtil.ACCESS_TOKEN_COOKIE, "new-access-token").build();
        ResponseCookie refreshCookie = ResponseCookie.from(CookieUtil.REFRESH_TOKEN_COOKIE, "new-refresh-token").build();
        when(refreshTokenService.rotateRefreshToken("old-refresh-token")).thenReturn(tokens);
        when(cookieUtil.buildAccessTokenCookie("new-access-token")).thenReturn(accessCookie);
        when(cookieUtil.buildRefreshTokenCookie("new-refresh-token")).thenReturn(refreshCookie);

        ResponseEntity<Void> result = authController.refresh("old-refresh-token", response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).addCookie(response, accessCookie);
        verify(cookieUtil).addCookie(response, refreshCookie);
    }

    @Test
    @DisplayName("[refresh] Should Pass Refresh Token Cookie Value To Service - When Called")
    void shouldPassRefreshTokenCookieValueToServiceWhenCalled() {
        when(refreshTokenService.rotateRefreshToken("old-refresh-token"))
                .thenReturn(new RefreshedTokens("new-access-token", "new-refresh-token"));

        authController.refresh("old-refresh-token", response);

        verify(refreshTokenService).rotateRefreshToken("old-refresh-token");
    }

    @Test
    @DisplayName("[logout] Should Return NoContent And Clear Access, Refresh And Csrf Cookies - When Called")
    void shouldReturnNoContentAndClearAccessRefreshAndCsrfCookiesWhenCalled() {
        ResponseCookie clearedCookie = ResponseCookie.from("cleared", "").build();
        when(cookieUtil.clearCookie(any(), any())).thenReturn(clearedCookie);

        ResponseEntity<Void> result = authController.logout("some-refresh-token", response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/");
        verify(cookieUtil).clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, CookieUtil.REFRESH_TOKEN_PATH);
        verify(cookieUtil).clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/");
        verify(cookieUtil, times(3)).addCookie(eq(response), eq(clearedCookie));
    }

    @Test
    @DisplayName("[logout] Should Revoke Refresh Token - When Called")
    void shouldRevokeRefreshTokenWhenCalled() {
        authController.logout("some-refresh-token", response);

        verify(refreshTokenService).revokeRefreshToken("some-refresh-token");
    }
}
