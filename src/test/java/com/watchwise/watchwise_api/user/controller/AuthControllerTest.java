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
import com.watchwise.watchwise_api.user.dto.ConfirmEmailDTO;
import com.watchwise.watchwise_api.user.dto.OAuthAccountNotFoundDTO;
import com.watchwise.watchwise_api.user.dto.OAuthLoginDTO;
import com.watchwise.watchwise_api.user.dto.PostUserDTO;
import com.watchwise.watchwise_api.user.dto.ResendEmailConfirmationDTO;
import com.watchwise.watchwise_api.user.dto.UserResponseDTO;
import com.watchwise.watchwise_api.user.service.AuthenticatedUser;
import com.watchwise.watchwise_api.user.service.UserService;
import com.watchwise.watchwise_api.user.service.EmailConfirmationService;
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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private EmailConfirmationService emailConfirmationService;

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
    @DisplayName("[register] Should Return Accepted Without A User DTO - When Registration Is Pending")
    void shouldReturnAcceptedWithoutUserDtoWhenRegistrationIsPending() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.startRegistration(postUserDTO)).thenReturn("registration-proof");

        ResponseEntity<Void> result = authController.register(postUserDTO, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isNull();
    }

    @Test
    @DisplayName("[register] Should Throw ConflictException And Not Create User - When Already Authenticated")
    void shouldThrowConflictExceptionAndNotCreateUserWhenAlreadyAuthenticated() {
        setAuthenticated();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);

        assertThatThrownBy(() -> authController.register(postUserDTO, request, response))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Already authenticated");

        verify(userService, never()).startRegistration(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[register] Should Send Confirmation Without Creating A Session - When Delivery Is Configured")
    void shouldSendConfirmationWithoutCreatingASessionWhenDeliveryIsConfigured() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.startRegistration(postUserDTO)).thenReturn("registration-proof");

        authController.register(postUserDTO, request, response);

        verify(userService).startRegistration(postUserDTO);
        verify(cookieUtil).buildRegistrationProofCookie("registration-proof");
        verify(cookieUtil).addCookie(eq(response), any());
        verifyNoInteractions(csrfAuthenticationStrategy, jwtService, refreshTokenService);
    }

    @Test
    @DisplayName("[resendEmailConfirmation] Should Renew Browser Proof Cookie - When Registration Proof Is Valid")
    void shouldRenewBrowserProofCookieWhenRegistrationProofIsValid() {
        ResendEmailConfirmationDTO confirmation = new ResendEmailConfirmationDTO("john.doe@email.com");
        ResponseCookie refreshedProof = ResponseCookie.from(CookieUtil.REGISTRATION_PROOF_COOKIE, "browser-proof")
                .maxAge(java.time.Duration.ofMinutes(30)).build();
        when(emailConfirmationService.resend("john.doe@email.com", "browser-proof")).thenReturn(true);
        when(cookieUtil.buildRegistrationProofCookie("browser-proof")).thenReturn(refreshedProof);

        ResponseEntity<Void> result = authController.resendEmailConfirmation(
                confirmation, "browser-proof", request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).buildRegistrationProofCookie("browser-proof");
        verify(cookieUtil).addCookie(response, refreshedProof);
    }

    @Test
    @DisplayName("[resendEmailConfirmation] Should Not Renew Browser Proof Cookie - When Registration Proof Is Invalid")
    void shouldNotRenewBrowserProofCookieWhenRegistrationProofIsInvalid() {
        ResendEmailConfirmationDTO confirmation = new ResendEmailConfirmationDTO("john.doe@email.com");
        when(emailConfirmationService.resend("john.doe@email.com", "wrong-proof")).thenReturn(false);

        ResponseEntity<Void> result = authController.resendEmailConfirmation(
                confirmation, "wrong-proof", request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil, never()).buildRegistrationProofCookie(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[confirmEmail] Should Issue Session Cookies - When Registration Proof And Email Token Are Valid")
    void shouldIssueSessionCookiesAfterRegistrationConfirmation() {
        UUID userId = userResponseDTO.id();
        when(emailConfirmationService.confirm("email-token", "browser-proof"))
                .thenReturn(new EmailConfirmationService.ConfirmationResult(userId, true));
        when(userService.getCurrentUser(userId)).thenReturn(userResponseDTO);
        when(userService.getAuthVersion(userId)).thenReturn(3L);
        when(jwtService.generateToken(userId, 3L, TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userId, 3L)).thenReturn("refresh-token");
        when(cookieUtil.clearRegistrationProofCookie())
                .thenReturn(ResponseCookie.from(CookieUtil.REGISTRATION_PROOF_COOKIE, "").maxAge(0).build());
        when(cookieUtil.buildAccessTokenCookie("access-token"))
                .thenReturn(ResponseCookie.from(CookieUtil.ACCESS_TOKEN_COOKIE, "access-token").build());
        when(cookieUtil.buildRefreshTokenCookie("refresh-token"))
                .thenReturn(ResponseCookie.from(CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token").build());

        ResponseEntity<Void> result = authController.confirmEmail(
                new ConfirmEmailDTO("email-token"), "browser-proof", request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).clearRegistrationProofCookie();
        verify(csrfAuthenticationStrategy).onAuthentication(null, request, response);
        verify(refreshTokenService).issueRefreshToken(userId, 3L);
        verify(cookieUtil, times(3)).addCookie(eq(response), any());
    }

    @Test
    @DisplayName("[confirmEmail] Should Not Issue Session - When Confirming An Email Change")
    void shouldNotIssueSessionWhenConfirmingAnEmailChange() {
        when(emailConfirmationService.confirm("email-token", null))
                .thenReturn(new EmailConfirmationService.ConfirmationResult(userResponseDTO.id(), false));
        when(cookieUtil.clearRegistrationProofCookie())
                .thenReturn(ResponseCookie.from(CookieUtil.REGISTRATION_PROOF_COOKIE, "").maxAge(0).build());

        ResponseEntity<Void> result = authController.confirmEmail(
                new ConfirmEmailDTO("email-token"), null, request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(cookieUtil).clearRegistrationProofCookie();
        verify(userService, never()).getCurrentUser(any());
        verifyNoInteractions(csrfAuthenticationStrategy, jwtService, refreshTokenService);
    }

    @Test
    @DisplayName("[register] Should Check Rate Limit Before Creating User - When Not Authenticated")
    void shouldCheckRateLimitBeforeCreatingUserWhenNotAuthenticated() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(userService.startRegistration(postUserDTO)).thenReturn("registration-proof");

        authController.register(postUserDTO, request, response);

        InOrder order = inOrder(requestThrottler, userService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(userService).startRegistration(postUserDTO);
    }

    @Test
    @DisplayName("[register] Should Build Throttle Key From Action And Remote Addr - When Called")
    void shouldBuildThrottleKeyFromActionAndRemoteAddrWhenRegisterCalled() {
        setAnonymous();
        PostUserDTO postUserDTO = new PostUserDTO("JohnDoe", "john.doe@email.com", "Password123", null, null, null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(userService.startRegistration(postUserDTO)).thenReturn("registration-proof");

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

        verify(userService, never()).startRegistration(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Return Ok With UserResponseDTO - When Not Authenticated")
    void shouldReturnOkWithUserResponseDtoWhenNotAuthenticated() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));

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

        verify(userService, never()).loginForAuthentication(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Set Access And Refresh Token Cookies - When Login Succeeds")
    void shouldSetAccessAndRefreshTokenCookiesWhenLoginSucceeds() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        ResponseCookie accessCookie = ResponseCookie.from(CookieUtil.ACCESS_TOKEN_COOKIE, "access-token").build();
        ResponseCookie refreshCookie = ResponseCookie.from(CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token").build();
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));
        when(jwtService.generateToken(userResponseDTO.id(), 0L, TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id(), 0L)).thenReturn("refresh-token");
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
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));

        authController.login(loginUserDTO, request, response);

        InOrder order = inOrder(attemptLockout, userService);
        order.verify(attemptLockout).checkAllowed(any(), anyInt(), any());
        order.verify(userService).loginForAuthentication(loginUserDTO);
    }

    @Test
    @DisplayName("[login] Should Check Ip Rate Limit Before Checking Lockout - When Not Authenticated")
    void shouldCheckIpRateLimitBeforeCheckingLockoutWhenNotAuthenticated() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));

        authController.login(loginUserDTO, request, response);

        InOrder order = inOrder(requestThrottler, attemptLockout, userService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(attemptLockout).checkAllowed(any(), anyInt(), any());
        order.verify(userService).loginForAuthentication(loginUserDTO);
    }

    @Test
    @DisplayName("[login] Should Build Ip Throttle Key From Action And Remote Addr - When Called")
    void shouldBuildIpThrottleKeyFromActionAndRemoteAddrWhenLoginCalled() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));

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

        verify(attemptLockout, never()).checkAllowed(any(), anyInt(), any());
        verify(userService, never()).loginForAuthentication(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Build Lockout Key From Identifier Only - When Called")
    void shouldBuildLockoutKeyFromIdentifierOnlyWhenCalled() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("  John.Doe@Email.com  ", "Password123");
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));

        authController.login(loginUserDTO, request, response);

        verify(attemptLockout).checkAllowed(eq("login|john.doe@email.com"), anyInt(), any());
    }

    @Test
    @DisplayName("[login] Should Throw TooManyRequestsException And Not Attempt Login - When Rate Limited")
    void shouldThrowTooManyRequestsExceptionAndNotAttemptLoginWhenRateLimited() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        doThrow(new TooManyRequestsException("Too many attempts. Try again later."))
                .when(attemptLockout).checkAllowed(any(), anyInt(), any());

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
        when(userService.loginForAuthentication(loginUserDTO)).thenThrow(new UnauthorizedException("Invalid credentials"));

        assertThatThrownBy(() -> authController.login(loginUserDTO, request, response))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid credentials");

        verify(attemptLockout).recordFailure(any(), anyInt(), any());
        verify(attemptLockout, never()).recordSuccess(any());
        verify(cookieUtil, never()).addCookie(any(), any());
    }

    @Test
    @DisplayName("[login] Should Record Success And Not Record Failure - When Login Succeeds")
    void shouldRecordSuccessAndNotRecordFailureWhenLoginSucceeds() {
        setAnonymous();
        LoginUserDTO loginUserDTO = new LoginUserDTO("john.doe@email.com", "Password123");
        when(userService.loginForAuthentication(loginUserDTO)).thenReturn(new AuthenticatedUser(userResponseDTO, 0L));

        authController.login(loginUserDTO, request, response);

        verify(attemptLockout).recordSuccess(any());
        verify(attemptLockout, never()).recordFailure(any(), anyInt(), any());
    }

    @Test
    @DisplayName("[oauthLogin] Should Return Ok With UserResponseDTO - When Account With Verified Email Exists")
    void shouldReturnOkWithUserResponseDtoWhenAccountWithVerifiedEmailExists() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        when(googleTokenVerifier.verify("google-id-token")).thenReturn(userResponseDTO.email());
        when(userService.findByEmailForAuthentication(userResponseDTO.email()))
                .thenReturn(Optional.of(new AuthenticatedUser(userResponseDTO, 0L)));

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
        when(userService.findByEmailForAuthentication(userResponseDTO.email()))
                .thenReturn(Optional.of(new AuthenticatedUser(userResponseDTO, 0L)));
        when(jwtService.generateToken(userResponseDTO.id(), 0L, TokenType.ACCESS)).thenReturn("access-token");
        when(refreshTokenService.issueRefreshToken(userResponseDTO.id(), 0L)).thenReturn("refresh-token");
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
        when(userService.findByEmailForAuthentication("unregistered@email.com")).thenReturn(Optional.empty());

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
        verify(userService, never()).findByEmailForAuthentication(any());
    }

    @Test
    @DisplayName("[oauthLogin] Should Check Rate Limit Before Verifying Token - When Not Authenticated")
    void shouldCheckRateLimitBeforeVerifyingTokenWhenNotAuthenticated() {
        setAnonymous();
        OAuthLoginDTO oAuthLoginDTO = new OAuthLoginDTO("google-id-token");
        when(googleTokenVerifier.verify("google-id-token")).thenReturn(userResponseDTO.email());
        when(userService.findByEmailForAuthentication(userResponseDTO.email()))
                .thenReturn(Optional.of(new AuthenticatedUser(userResponseDTO, 0L)));

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
        when(userService.findByEmailForAuthentication(userResponseDTO.email()))
                .thenReturn(Optional.of(new AuthenticatedUser(userResponseDTO, 0L)));

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
        verify(userService, never()).findByEmailForAuthentication(any());
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
        when(cookieUtil.getCsrfTokenPath()).thenReturn("/");
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

        verify(refreshTokenService).invalidateAllSessions(currentUserId);
    }

    @Test
    @DisplayName("[logoutAll] Should Clear Access, Refresh And Csrf Cookies - When Called")
    void shouldClearAccessRefreshAndCsrfCookiesWhenLogoutAllCalled() {
        setAuthenticated(UUID.randomUUID());
        ResponseCookie clearedCookie = ResponseCookie.from("cleared", "").build();
        when(cookieUtil.getRefreshTokenPath()).thenReturn("/api/v1/auth/refresh");
        when(cookieUtil.getCsrfTokenPath()).thenReturn("/");
        when(cookieUtil.clearCookie(any(), any())).thenReturn(clearedCookie);

        authController.logoutAll(response);

        verify(cookieUtil).clearCookie(CookieUtil.ACCESS_TOKEN_COOKIE, "/");
        verify(cookieUtil).clearCookie(CookieUtil.REFRESH_TOKEN_COOKIE, "/api/v1/auth/refresh");
        verify(cookieUtil).clearCookie(CookieUtil.CSRF_TOKEN_COOKIE, "/");
        verify(cookieUtil, times(3)).addCookie(eq(response), eq(clearedCookie));
    }
}
