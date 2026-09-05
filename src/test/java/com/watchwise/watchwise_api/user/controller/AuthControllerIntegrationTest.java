package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.GoogleTokenVerifier;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    @DisplayName("[register] Should Emit Access, Refresh And Csrf Cookies - When Registration Succeeds")
    void shouldEmitAccessRefreshAndCsrfCookiesWhenRegistrationSucceeds() throws Exception {
        mockMvc.perform(registerRequest("csrfuser", "csrfuser@email.com"))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andExpect(cookie().httpOnly(CookieUtil.CSRF_TOKEN_COOKIE, false));
    }

    @Test
    @DisplayName("[register] Should Replace A Pre-Existing Csrf Token - When A Stale Cookie Was Already Present")
    void shouldReplaceAPreExistingCsrfTokenWhenAStaleCookieWasAlreadyPresent() throws Exception {
        MvcResult anonymousResult = mockMvc.perform(get("/protected-test-route"))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andReturn();

        Cookie staleCsrfCookie = anonymousResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(staleCsrfCookie).isNotNull();

        MvcResult registerResult = mockMvc.perform(registerRequest("rotateduser", "rotateduser@email.com")
                        .cookie(staleCsrfCookie))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie freshCsrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(freshCsrfCookie).isNotNull();
        assertThat(freshCsrfCookie.getValue()).isNotEqualTo(staleCsrfCookie.getValue());
    }

    @Test
    @DisplayName("[register] Should Return BadRequest With Validation Errors - When Payload Is Invalid")
    void shouldReturnBadRequestWithValidationErrorsWhenPayloadIsInvalid() throws Exception {
        String body = """
                {
                    "username": "ab",
                    "email": "not-an-email",
                    "password": "short",
                    "isProfilePublic": true
                }
                """;

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation Failed"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems("username", "email", "password")));
    }

    @Test
    @DisplayName("[register] Should Return Conflict With Username Message - When Username Is Already Taken")
    void shouldReturnConflictWithUsernameMessageWhenUsernameIsAlreadyTaken() throws Exception {
        mockMvc.perform(registerRequest("duplicateuser", "first@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(registerRequest("duplicateuser", "second@email.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[register] Should Return Conflict With Username Message - When Username Is Already Taken With Different Case")
    void shouldReturnConflictWithUsernameMessageWhenUsernameIsAlreadyTakenWithDifferentCase() throws Exception {
        mockMvc.perform(registerRequest("CaseUser", "casefirst@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(registerRequest("caseuser", "casesecond@email.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[register] Should Return Conflict With Email Message - When Email Is Already Taken")
    void shouldReturnConflictWithEmailMessageWhenEmailIsAlreadyTaken() throws Exception {
        mockMvc.perform(registerRequest("firstuser", "duplicate@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(registerRequest("seconduser", "duplicate@email.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already in use"));
    }

    @Test
    @DisplayName("[register] Should Return Conflict - When Already Authenticated")
    void shouldReturnConflictWhenAlreadyAuthenticated() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("alreadyauthuser", "alreadyauthuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(registerRequest("anotheruser", "anotheruser@email.com").cookie(accessTokenCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already authenticated"));
    }

    @Test
    @DisplayName("[register] Should Return TooManyRequests - When Requests From Same Ip Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenRegisterRequestsFromSameIpExceedTheConfiguredMax() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(registerRequest("throttleuser" + i, "throttleuser" + i + "@email.com"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(registerRequest("throttleuser10", "throttleuser10@email.com"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[register] Should Not Block A Different Ip - When Another Ip Is Rate Limited")
    void shouldNotBlockADifferentIpWhenAnotherIpIsRateLimitedForRegister() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(registerRequest("ipthrottleuser" + i, "ipthrottleuser" + i + "@email.com"))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(registerRequest("ipthrottleuser10", "ipthrottleuser10@email.com"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(registerRequest("ipthrottleuser11", "ipthrottleuser11@email.com").with(req -> {
                    req.setRemoteAddr("10.0.0.99");
                    return req;
                }))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("[login] Should Return Conflict - When Already Authenticated")
    void shouldReturnConflictWhenLoggingInWhileAlreadyAuthenticated() throws Exception {
        mockMvc.perform(registerRequest("alreadyauthloginuser", "alreadyauthloginuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult registerResult = mockMvc.perform(registerRequest("otherloginuser", "otherloginuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(loginRequest("alreadyauthloginuser").cookie(accessTokenCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already authenticated"));
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO And Emit Access, Refresh And Csrf Cookies - When Credentials Are Valid")
    void shouldReturnUserResponseDtoAndEmitCookiesWhenCredentialsAreValid() throws Exception {
        mockMvc.perform(registerRequest("loginuser", "loginuser@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(loginRequest("loginuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("loginuser"))
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    @Test
    @DisplayName("[login] Should Return Unauthorized - When Password Is Incorrect")
    void shouldReturnUnauthorizedWhenPasswordIsIncorrect() throws Exception {
        mockMvc.perform(registerRequest("wrongpassuser", "wrongpassuser@email.com"))
                .andExpect(status().isCreated());

        String body = """
                {
                    "identifier": "wrongpassuser",
                    "password": "WrongPassword123"
                }
                """;

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @DisplayName("[login] Should Return Unauthorized - When Identifier Does Not Match Any User")
    void shouldReturnUnauthorizedWhenIdentifierDoesNotMatchAnyUser() throws Exception {
        mockMvc.perform(loginRequest("unknownuser"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    @DisplayName("[login] Should Resolve To The Username Match - When A Username Collides With A Different User's Email")
    void shouldResolveToTheUsernameMatchWhenAUsernameCollidesWithADifferentUsersEmail() throws Exception {
        mockMvc.perform(registerRequest("emailowner", "collision@email.com"))
                .andExpect(status().isCreated());
        mockMvc.perform(registerRequest("collision@email.com", "usernameowner@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(loginRequest("collision@email.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("collision@email.com"));
    }

    @Test
    @DisplayName("[login] Should Return TooManyRequests - When Failed Attempts Reach The Configured Max")
    void shouldReturnTooManyRequestsWhenFailedAttemptsReachTheConfiguredMax() throws Exception {
        mockMvc.perform(registerRequest("ratelimituser", "ratelimituser@email.com"))
                .andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(wrongPasswordLoginRequest("ratelimituser"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginRequest("ratelimituser"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many attempts. Try again later."));
    }

    @Test
    @DisplayName("[login] Should Not Block A Different Identifier - When Another Identifier Is Rate Limited")
    void shouldNotBlockADifferentIdentifierWhenAnotherIdentifierIsRateLimited() throws Exception {
        mockMvc.perform(registerRequest("ratelimituserA", "ratelimituserA@email.com"))
                .andExpect(status().isCreated());
        mockMvc.perform(registerRequest("ratelimituserB", "ratelimituserB@email.com"))
                .andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(wrongPasswordLoginRequest("ratelimituserA"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(loginRequest("ratelimituserA"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(loginRequest("ratelimituserB"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[login] Should Still Block A Different Ip - When Same Identifier Is Rate Limited")
    void shouldStillBlockADifferentIpWhenSameIdentifierIsRateLimited() throws Exception {
        mockMvc.perform(registerRequest("ratelimitipuser", "ratelimitipuser@email.com"))
                .andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(wrongPasswordLoginRequest("ratelimitipuser"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(loginRequest("ratelimitipuser"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(loginRequest("ratelimitipuser").with(req -> {
                    req.setRemoteAddr("10.0.0.99");
                    return req;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many attempts. Try again later."));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return Ok And Emit Access, Refresh And Csrf Cookies - When Local Account With That Email Exists")
    void shouldReturnOkAndEmitCookiesWhenLocalAccountWithThatEmailExists() throws Exception {
        mockMvc.perform(registerRequest("oauthuser", "oauthuser@email.com"))
                .andExpect(status().isCreated());

        when(googleTokenVerifier.verify("valid-google-token")).thenReturn("oauthuser@email.com");

        mockMvc.perform(oauthLoginRequest("google", "valid-google-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("oauthuser@email.com"))
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return NotFound With Confirmed Email - When No Local Account Has That Email")
    void shouldReturnNotFoundWithConfirmedEmailWhenNoLocalAccountHasThatEmail() throws Exception {
        when(googleTokenVerifier.verify("valid-google-token")).thenReturn("unregistered@email.com");

        mockMvc.perform(oauthLoginRequest("google", "valid-google-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.email").value("unregistered@email.com"));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return Unauthorized - When Provider Email Is Not Verified")
    void shouldReturnUnauthorizedWhenProviderEmailIsNotVerified() throws Exception {
        when(googleTokenVerifier.verify("unverified-email-token"))
                .thenThrow(new UnauthorizedException("Email not verified by provider"));

        mockMvc.perform(oauthLoginRequest("google", "unverified-email-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email not verified by provider"));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return Unauthorized - When Provider Token Is Invalid Or Expired")
    void shouldReturnUnauthorizedWhenProviderTokenIsInvalidOrExpired() throws Exception {
        when(googleTokenVerifier.verify("bad-token"))
                .thenThrow(new UnauthorizedException("Invalid or expired provider token"));

        mockMvc.perform(oauthLoginRequest("google", "bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired provider token"));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return BadRequest - When Token Is Blank")
    void shouldReturnBadRequestWhenOAuthTokenIsBlank() throws Exception {
        mockMvc.perform(oauthLoginRequest("google", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation Failed"));

        verifyNoInteractions(googleTokenVerifier);
    }

    @Test
    @DisplayName("[oauthLogin] Should Return BadRequest - When Provider Is Not Supported")
    void shouldReturnBadRequestWhenProviderIsNotSupported() throws Exception {
        mockMvc.perform(oauthLoginRequest("facebook", "some-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[oauthLogin] Should Return Conflict - When Already Authenticated")
    void shouldReturnConflictWhenOAuthLoginWhileAlreadyAuthenticated() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("alreadyauthoauthuser", "alreadyauthoauthuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(oauthLoginRequest("google", "valid-google-token").cookie(accessTokenCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already authenticated"));
    }

    @Test
    @DisplayName("[oauthLogin] Should Return TooManyRequests - When Requests From Same Ip Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenOauthRequestsFromSameIpExceedTheConfiguredMax() throws Exception {
        when(googleTokenVerifier.verify("valid-google-token")).thenReturn("unregistered@email.com");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(oauthLoginRequest("google", "valid-google-token"))
                    .andExpect(status().isNotFound());
        }

        mockMvc.perform(oauthLoginRequest("google", "valid-google-token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[refresh] Should Return Unauthorized - When No Refresh Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoRefreshTokenCookieIsPresent() throws Exception {
        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
    }

    @Test
    @DisplayName("[refresh] Should Return TooManyRequests - When Requests From Same Ip Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenRefreshRequestsFromSameIpExceedTheConfiguredMax() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(post("/auth/refresh"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[refresh] Should Revoke Every Valid Refresh Token For The User - When A Revoked Refresh Token Is Reused")
    void shouldRevokeEveryValidRefreshTokenForTheUserWhenARevokedRefreshTokenIsReused() throws Exception {
        mockMvc.perform(registerRequest("reuseuser", "reuseuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(loginRequest("reuseuser"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie originalRefreshCookie = loginResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(originalRefreshCookie).isNotNull();

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie rotatedRefreshCookie = refreshResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(rotatedRefreshCookie).isNotNull();

        mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/auth/refresh").cookie(rotatedRefreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[refresh][logout] Should Rotate Refresh Token And Reject Reuse Of Old Token - When Full Auth Flow Is Executed")
    void shouldRotateRefreshTokenAndRejectReuseOfOldTokenWhenFullAuthFlowIsExecuted() throws Exception {
        mockMvc.perform(registerRequest("flowuser", "flowuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(loginRequest("flowuser"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andReturn();

        Cookie originalRefreshCookie = loginResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(originalRefreshCookie).isNotNull();

        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andReturn();

        Cookie rotatedRefreshCookie = refreshResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(rotatedRefreshCookie).isNotNull();
        assertThat(rotatedRefreshCookie.getValue()).isNotEqualTo(originalRefreshCookie.getValue());

        mockMvc.perform(post("/auth/refresh").cookie(originalRefreshCookie))
                .andExpect(status().isUnauthorized());

        MvcResult logoutResult = mockMvc.perform(post("/auth/logout").cookie(rotatedRefreshCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andReturn();
        assertCsrfCookieCleared(logoutResult);

        mockMvc.perform(post("/auth/refresh").cookie(rotatedRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("[logout] Should Clear Cookies - When No Refresh Token Cookie Is Present")
    void shouldClearCookiesWhenNoRefreshTokenCookieIsPresent() throws Exception {
        MvcResult logoutResult = mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andReturn();
        assertCsrfCookieCleared(logoutResult);
    }

    @Test
    @DisplayName("[logoutAll] Should Revoke Every Refresh Token For The User - When Called")
    void shouldRevokeEveryRefreshTokenForTheUserWhenLogoutAllCalled() throws Exception {
        mockMvc.perform(registerRequest("multideviceuser", "multideviceuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult deviceALogin = mockMvc.perform(loginRequest("multideviceuser"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie deviceAAccessCookie = deviceALogin.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie deviceARefreshCookie = deviceALogin.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        Cookie deviceACsrfCookie = deviceALogin.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(deviceAAccessCookie).isNotNull();
        assertThat(deviceARefreshCookie).isNotNull();
        assertThat(deviceACsrfCookie).isNotNull();

        MvcResult deviceBLogin = mockMvc.perform(loginRequest("multideviceuser"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie deviceBRefreshCookie = deviceBLogin.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(deviceBRefreshCookie).isNotNull();

        mockMvc.perform(logoutAllRequest(deviceAAccessCookie, deviceACsrfCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh").cookie(deviceARefreshCookie))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/refresh").cookie(deviceBRefreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[logoutAll] Should Invalidate The Access Token Already Issued - When Called")
    void shouldInvalidateTheAccessTokenAlreadyIssuedWhenLogoutAllCalled() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("accesstokeninvalidateuser", "accesstokeninvalidateuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk());

        mockMvc.perform(logoutAllRequest(accessTokenCookie, csrfCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[logoutAll] Should Not Affect Refresh Tokens Of Other Users - When Called")
    void shouldNotAffectRefreshTokensOfOtherUsersWhenLogoutAllCalled() throws Exception {
        MvcResult userARegister = mockMvc.perform(registerRequest("logoutalluserA", "logoutalluserA@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie userAAccessCookie = userARegister.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie userACsrfCookie = userARegister.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(userAAccessCookie).isNotNull();
        assertThat(userACsrfCookie).isNotNull();

        MvcResult userBRegister = mockMvc.perform(registerRequest("logoutalluserB", "logoutalluserB@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie userBRefreshCookie = userBRegister.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(userBRefreshCookie).isNotNull();

        mockMvc.perform(logoutAllRequest(userAAccessCookie, userACsrfCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh").cookie(userBRefreshCookie))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[logoutAll] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForLogoutAll() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocookielogoutalluser", "nocookielogoutalluser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(post("/auth/logout-all")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[logoutAll] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForLogoutAll() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocsrflogoutalluser", "nocsrflogoutalluser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(post("/auth/logout-all").cookie(accessTokenCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[logoutAll] Should Clear Access, Refresh And Csrf Cookies - When Called")
    void shouldClearAccessRefreshAndCsrfCookiesWhenLogoutAllCalled() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("clearcookieslogoutalluser", "clearcookieslogoutalluser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        MvcResult logoutAllResult = mockMvc.perform(logoutAllRequest(accessTokenCookie, csrfCookie))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andReturn();
        assertCsrfCookieCleared(logoutAllResult);
    }

    private void assertCsrfCookieCleared(MvcResult result) {
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(header -> assertThat(header)
                        .startsWith(CookieUtil.CSRF_TOKEN_COOKIE + "=")
                        .contains("Max-Age=0"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder registerRequest(String username, String email) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": true
                }
                """.formatted(username, email);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String identifier) {
        String body = """
                {
                    "identifier": "%s",
                    "password": "Password123"
                }
                """.formatted(identifier);

        return post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder wrongPasswordLoginRequest(String identifier) {
        String body = """
                {
                    "identifier": "%s",
                    "password": "WrongPassword123"
                }
                """.formatted(identifier);

        return post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder oauthLoginRequest(String provider, String token) {
        String body = """
                {
                    "token": "%s"
                }
                """.formatted(token);

        return post("/auth/oauth/{provider}", provider)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder logoutAllRequest(Cookie accessTokenCookie, Cookie csrfCookie) {
        return post("/auth/logout-all")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue());
    }
}
