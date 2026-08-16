package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.dto.RefreshedTokens;
import com.watchwise.watchwise_api.auth.service.RefreshTokenService;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.AttemptLockout;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.GoogleTokenVerifier;
import com.watchwise.watchwise_api.common.security.JwtService;
import com.watchwise.watchwise_api.common.security.OAuthProvider;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.TokenType;
import com.watchwise.watchwise_api.user.dto.LoginUserDTO;
import com.watchwise.watchwise_api.user.dto.OAuthAccountNotFoundDTO;
import com.watchwise.watchwise_api.user.dto.OAuthLoginDTO;
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
import org.mockito.InOrder;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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

    @Mock
    private GoogleTokenVerifier googleTokenVerifier;

    @Mock
    private AttemptLockout attemptLockout;

    @Mock
    private RequestThrottler requestThrottler;

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
        setAuthenticated(UUID.randomUUID());
    }

    private void setAuthenticated(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of())
        );
    }

    @Test
    @DisplayName("[register] Should Return Created With UserResponseDTO - When Not Authenticated")
    void shouldReturnCreatedWithUserResponseDtoWhenNotAuthenticated() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.saveNewUser(postUserDTO)).thenReturn(userResponseDTO);
        when(jwtService.generateToken(userResponseDTO.id(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id())).thenReturn("refresh-token");

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
        when(jwtService.generateToken(userResponseDTO.id(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id())).thenReturn("refresh-token");
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
    @DisplayName("[register] Should Check Rate Limit Before Creating User - When Not Authenticated")
    void shouldCheckRateLimitBeforeCreatingUserWhenNotAuthenticated() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.saveNewUser(postUserDTO)).thenReturn(userResponseDTO);

        authController.register(postUserDTO, request, response);

        InOrder order = inOrder(requestThrottler, userService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(userService).saveNewUser(postUserDTO);
    }

    @Test
    @DisplayName("[register] Should Build Throttle Key From Action And Remote Addr - When Called")
    void shouldBuildThrottleKeyFromActionAndRemoteAddrWhenRegisterCalled() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(userService.saveNewUser(postUserDTO)).thenReturn(userResponseDTO);

        authController.register(postUserDTO, request, response);

        verify(requestThrottler).checkAllowed(eq("register|127.0.0.1"), anyInt(), any());
    }

    @Test
    @DisplayName("[register] Should Throw TooManyRequestsException And Not Create User - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotCreateUserWhenRateLimited() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(any(), anyInt(), any());

        assertThatThrownBy(() -> authController.register(postUserDTO, request, response))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userService, never()).saveNewUser(any());
        verify(cookieUtil, never()).addCookie(any(), any());
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
        when(jwtService.generateToken(userResponseDTO.id(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id())).thenReturn("refresh-token");
        when(cookieUtil.buildAccessTokenCookie("access-token")).thenReturn(accessCookie);
        when(cookieUtil.buildRefreshTokenCookie("refresh-token")).thenReturn(refreshCookie);

        authController.login(loginUserDTO, request, response);

        verify(cookieUtil).addCookie(response, accessCookie);
        verify(cookieUtil).addCookie(response, refreshCookie);
    }

    @Test
    @DisplayName("[login] Should Check Rate Limit Before Attempting Login - When Not Authenticated")
    void shouldCheckRateLimitBeforeAttemptingLoginWhenNotAuthenticated() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);

        authController.login(loginUserDTO, request, response);

        InOrder order = inOrder(attemptLockout, userService);
        order.verify(attemptLockout).checkAllowed(any());
        order.verify(userService).login(loginUserDTO);
    }

    @Test
    @DisplayName("[login] Should Check Ip Rate Limit Before Checking Lockout - When Not Authenticated")
    void shouldCheckIpRateLimitBeforeCheckingLockoutWhenNotAuthenticated() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);

        authController.login(loginUserDTO, request, response);

        InOrder order = inOrder(requestThrottler, attemptLockout, userService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(attemptLockout).checkAllowed(any());
        order.verify(userService).login(loginUserDTO);
    }

    @Test
    @DisplayName("[login] Should Build Ip Throttle Key From Action And Remote Addr - When Called")
    void shouldBuildIpThrottleKeyFromActionAndRemoteAddrWhenLoginCalled() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);

        authController.login(loginUserDTO, request, response);

        verify(requestThrottler).checkAllowed(eq("login|127.0.0.1"), anyInt(), any());
    }

    @Test
    @DisplayName("[login] Should Throw TooManyRequestsException And Not Check Lockout - When Ip Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotCheckLockoutWhenIpRateLimited() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(any(), anyInt(), any());

        assertThatThrownBy(() -> authController.login(loginUserDTO, request, response))
                .isInstanceOf(TooManyRequestsException.class);

        verify(attemptLockout, never()).checkAllowed(any());
        verify(userService, never()).login(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Build Lockout Key From Remote Addr And Identifier - When Called")
    void shouldBuildLockoutKeyFromRemoteAddrAndIdentifierWhenCalled() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("  John.Doe@Email.com  ", "Password123");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);

        authController.login(loginUserDTO, request, response);

        verify(attemptLockout).checkAllowed("login|127.0.0.1|john.doe@email.com");
    }

    @Test
    @DisplayName("[login] Should Throw TooManyRequestsException And Not Attempt Login - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotAttemptLoginWhenRateLimited() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        doThrow(new TooManyRequestsException("Too many attempts. Try again later."))
                .when(attemptLockout).checkAllowed(any());

        assertThatThrownBy(() -> authController.login(loginUserDTO, request, response))
                .isInstanceOf(TooManyRequestsException.class);

        verify(userService, never()).login(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Record Failure And Rethrow - When Credentials Are Invalid")
    void shouldRecordFailureAndRethrowWhenCredentialsAreInvalid() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "WrongPassword123");
        when(userService.login(loginUserDTO)).thenThrow(new UnauthorizedException("Invalid credentials"));

        assertThatThrownBy(() -> authController.login(loginUserDTO, request, response))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid credentials");

        verify(attemptLockout).recordFailure(any(), anyInt(), any(), any());
        verify(attemptLockout, never()).recordSuccess(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Record Success And Not Record Failure - When Login Succeeds")
    void shouldRecordSuccessAndNotRecordFailureWhenLoginSucceeds() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.login(loginUserDTO)).thenReturn(userResponseDTO);

        authController.login(loginUserDTO, request, response);

        verify(attemptLockout).recordSuccess(any());
        verify(attemptLockout, never()).recordFailure(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("[oauthLogin] Should Return Ok With UserResponseDTO - When Account With Verified Email Exists")
    void shouldReturnOkWithUserResponseDtoWhenAccountWithVerifiedEmailExists() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        when(googleTokenVerifier.verify("google-id-token")).thenReturn(userResponseDTO.email());
        when(userService.findByEmail(userResponseDTO.email())).thenReturn(Optional.of(userResponseDTO));

        ResponseEntity<Object> result = authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(userResponseDTO);
    }

    @Test
    @DisplayName("[oauthLogin] Should Set Access And Refresh Token Cookies - When Account With Verified Email Exists")
    void shouldSetAccessAndRefreshTokenCookiesWhenAccountWithVerifiedEmailExists() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        ResponseCookie accessCookie = ResponseCookie.from(CookieUtil.ACCESS_TOKEN_COOKIE, "access-token").build();
        ResponseCookie refreshCookie = ResponseCookie.from(CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token").build();
        when(googleTokenVerifier.verify("google-id-token")).thenReturn(userResponseDTO.email());
        when(userService.findByEmail(userResponseDTO.email())).thenReturn(Optional.of(userResponseDTO));
        when(jwtService.generateToken(userResponseDTO.id(), TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id())).thenReturn("refresh-token");
        when(cookieUtil.buildAccessTokenCookie("access-token")).thenReturn(accessCookie);
        when(cookieUtil.buildRefreshTokenCookie("refresh-token")).thenReturn(refreshCookie);

        authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response);

        verify(cookieUtil).addCookie(response, accessCookie);
        verify(cookieUtil).addCookie(response, refreshCookie);
        verify(csrfAuthenticationStrategy).onAuthentication(eq(null), eq(request), eq(response));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return NotFound With Confirmed Email - When No Local Account Has That Email")
    void shouldReturnNotFoundWithConfirmedEmailWhenNoLocalAccountHasThatEmail() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        when(googleTokenVerifier.verify("google-id-token")).thenReturn("unregistered@email.com");
        when(userService.findByEmail("unregistered@email.com")).thenReturn(Optional.empty());

        ResponseEntity<Object> result = authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isEqualTo(new OAuthAccountNotFoundDTO("unregistered@email.com"));
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[oauthLogin] Should Throw ConflictException And Not Verify Token - When Already Authenticated")
    void shouldThrowConflictExceptionAndNotVerifyTokenWhenAlreadyAuthenticated() {
        setAuthenticated();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");

        assertThatThrownBy(() -> authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Already authenticated");

        verify(googleTokenVerifier, never()).verify(any());
        verify(userService, never()).findByEmail(any());
    }

    @Test
    @DisplayName("[oauthLogin] Should Check Rate Limit Before Verifying Token - When Not Authenticated")
    void shouldCheckRateLimitBeforeVerifyingTokenWhenNotAuthenticated() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        when(googleTokenVerifier.verify("google-id-token")).thenReturn(userResponseDTO.email());
        when(userService.findByEmail(userResponseDTO.email())).thenReturn(Optional.of(userResponseDTO));

        authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response);

        InOrder order = inOrder(requestThrottler, googleTokenVerifier);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(googleTokenVerifier).verify("google-id-token");
    }

    @Test
    @DisplayName("[oauthLogin] Should Build Throttle Key From Action And Remote Addr - When Called")
    void shouldBuildThrottleKeyFromActionAndRemoteAddrWhenOauthLoginCalled() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(googleTokenVerifier.verify("google-id-token")).thenReturn(userResponseDTO.email());
        when(userService.findByEmail(userResponseDTO.email())).thenReturn(Optional.of(userResponseDTO));

        authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response);

        verify(requestThrottler).checkAllowed(eq("oauth|127.0.0.1"), anyInt(), any());
    }

    @Test
    @DisplayName("[oauthLogin] Should Throw TooManyRequestsException And Not Verify Token - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotVerifyTokenWhenRateLimited() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(any(), anyInt(), any());

        assertThatThrownBy(() -> authController.oauthLogin(OAuthProvider.GOOGLE, oAuthLoginDTO, request, response))
                .isInstanceOf(TooManyRequestsException.class);

        verify(googleTokenVerifier, never()).verify(any());
        verify(userService, never()).findByEmail(any());
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

        ResponseEntity<Void> result = authController.refresh("old-refresh-token", request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).addCookie(response, accessCookie);
        verify(cookieUtil).addCookie(response, refreshCookie);
    }

    @Test
    @DisplayName("[refresh] Should Pass Refresh Token Cookie Value To Service - When Called")
    void shouldPassRefreshTokenCookieValueToServiceWhenCalled() {
        when(refreshTokenService.rotateRefreshToken("old-refresh-token"))
                .thenReturn(new RefreshedTokens("new-access-token", "new-refresh-token"));

        authController.refresh("old-refresh-token", request, response);

        verify(refreshTokenService).rotateRefreshToken("old-refresh-token");
    }

    @Test
    @DisplayName("[refresh] Should Check Rate Limit Before Rotating Token - When Called")
    void shouldCheckRateLimitBeforeRotatingTokenWhenCalled() {
        when(refreshTokenService.rotateRefreshToken("old-refresh-token"))
                .thenReturn(new RefreshedTokens("new-access-token", "new-refresh-token"));

        authController.refresh("old-refresh-token", request, response);

        InOrder order = inOrder(requestThrottler, refreshTokenService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(refreshTokenService).rotateRefreshToken("old-refresh-token");
    }

    @Test
    @DisplayName("[refresh] Should Build Throttle Key From Action And Remote Addr - When Called")
    void shouldBuildThrottleKeyFromActionAndRemoteAddrWhenRefreshCalled() {
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(refreshTokenService.rotateRefreshToken("old-refresh-token"))
                .thenReturn(new RefreshedTokens("new-access-token", "new-refresh-token"));

        authController.refresh("old-refresh-token", request, response);

        verify(requestThrottler).checkAllowed(eq("refresh|127.0.0.1"), anyInt(), any());
    }

    @Test
    @DisplayName("[refresh] Should Throw TooManyRequestsException And Not Rotate Token - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotRotateTokenWhenRateLimited() {
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler).checkAllowed(any(), anyInt(), any());

        assertThatThrownBy(() -> authController.refresh("old-refresh-token", request, response))
                .isInstanceOf(TooManyRequestsException.class);

        verify(refreshTokenService, never()).rotateRefreshToken(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[logout] Should Return NoContent And Clear Access, Refresh And Csrf Cookies - When Called")
    void shouldReturnNoContentAndClearAccessRefreshAndCsrfCookiesWhenCalled() {
        ResponseCookie clearedCookie = ResponseCookie.from("cleared", "").build();
        when(cookieUtil.getRefreshTokenPath()).thenReturn("/api/v1/auth/refresh");
        when(cookieUtil.clearCookie(any(), any())).thenReturn(clearedCookie);

        ResponseEntity<Void> result = authController.logout("some-refresh-token", response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/");
        verify(cookieUtil).clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, "/api/v1/auth/refresh");
        verify(cookieUtil).clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/");
        verify(cookieUtil, times(3)).addCookie(eq(response), eq(clearedCookie));
    }

    @Test
    @DisplayName("[logout] Should Revoke Refresh Token - When Called")
    void shouldRevokeRefreshTokenWhenCalled() {
        authController.logout("some-refresh-token", response);

        verify(refreshTokenService).revokeRefreshToken("some-refresh-token");
    }

    @Test
    @DisplayName("[logoutAll] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenLogoutAllCalled() {
        setAuthenticated(UUID.randomUUID());

        ResponseEntity<Void> result = authController.logoutAll(response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[logoutAll] Should Resolve Id From The Security Context - When Called")
    void shouldResolveIdFromTheSecurityContextWhenLogoutAllCalled() {
        UUID currentUserId = UUID.randomUUID();
        setAuthenticated(currentUserId);

        authController.logoutAll(response);

        verify(refreshTokenService).revokeAllRefreshTokens(currentUserId);
    }

    @Test
    @DisplayName("[logoutAll] Should Clear Access, Refresh And Csrf Cookies - When Called")
    void shouldClearAccessRefreshAndCsrfCookiesWhenLogoutAllCalled() {
        setAuthenticated(UUID.randomUUID());
        ResponseCookie clearedCookie = ResponseCookie.from("cleared", "").build();
        when(cookieUtil.getRefreshTokenPath()).thenReturn("/api/v1/auth/refresh");
        when(cookieUtil.clearCookie(any(), any())).thenReturn(clearedCookie);

        authController.logoutAll(response);

        verify(cookieUtil).clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/");
        verify(cookieUtil).clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, "/api/v1/auth/refresh");
        verify(cookieUtil).clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/");
        verify(cookieUtil, times(3)).addCookie(eq(response), eq(clearedCookie));
    }
}
