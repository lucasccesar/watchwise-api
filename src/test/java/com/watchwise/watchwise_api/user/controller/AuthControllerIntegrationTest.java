package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.GoogleTokenVerifier;
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

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
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
    @DisplayName("[refresh] Should Return Unauthorized - When No Refresh Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoRefreshTokenCookieIsPresent() throws Exception {
        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token"));
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
}
