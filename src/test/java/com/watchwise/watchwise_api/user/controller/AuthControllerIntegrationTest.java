package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.exception.UnauthorizedException;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.GoogleTokenVerifier;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.PendingRegistrationRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.user.service.impl.UserServiceImpl;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(image -> false);

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
    private PendingRegistrationRepository pendingRegistrationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestEmailConfirmationSender emailConfirmationSender;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private GoogleTokenVerifier googleTokenVerifier;

    @MockitoSpyBean
    private UserServiceImpl userService;

    @Autowired
    private RequestThrottler requestThrottler;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Map<String, Cookie> registrationProofCookies = new HashMap<>();

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        pendingRegistrationRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
        emailConfirmationSender.clear();
        registrationProofCookies.clear();
    }

    @Test
    void loginDoesNotIssueTokensOrCookiesWhenClearingLockoutFails() throws Exception {
        MvcResult login = registerConfirmAndLogin("clearfailure", "clearfailure@example.test");
        var refreshBefore = jdbcTemplate.queryForList("SELECT id, revoked FROM refresh_tokens ORDER BY id");
        long attemptsBefore = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(request_count), 0) FROM shared_limiter_state WHERE kind = 'lockout'", Long.class);
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_login_lockout_clear() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test lockout clear unavailable'; END $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_login_lockout_clear BEFORE DELETE ON shared_limiter_state
                FOR EACH ROW WHEN (OLD.kind = 'lockout') EXECUTE FUNCTION reject_login_lockout_clear()
                """);
        try {
            mockMvc.perform(loginRequest("clearfailure")
                            .cookie(login.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(cookie().doesNotExist(CookieUtil.ACCESS_TOKEN_COOKIE))
                    .andExpect(cookie().doesNotExist(CookieUtil.REFRESH_TOKEN_COOKIE))
                    .andExpect(cookie().doesNotExist(CookieUtil.CSRF_TOKEN_COOKIE));
            assertThat(jdbcTemplate.queryForList("SELECT id, revoked FROM refresh_tokens ORDER BY id"))
                    .isEqualTo(refreshBefore);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(request_count), 0) FROM shared_limiter_state WHERE kind = 'lockout'", Long.class))
                    .isEqualTo(attemptsBefore + 1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_login_lockout_clear ON shared_limiter_state");
            jdbcTemplate.execute("DROP FUNCTION reject_login_lockout_clear()");
        }
    }

    @Test
    @DisplayName("[register] Should Not Create A Session Before Email Confirmation")
    void shouldNotCreateASessionBeforeEmailConfirmation() throws Exception {
        mockMvc.perform(registerRequest("csrfuser", "csrfuser@email.com"))
                .andExpect(status().isAccepted())
                .andExpect(cookie().exists(CookieUtil.REGISTRATION_PROOF_COOKIE))
                .andExpect(cookie().doesNotExist(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().doesNotExist(CookieUtil.REFRESH_TOKEN_COOKIE));

        assertThat(userRepository.findByEmailIgnoreCase("csrfuser@email.com")).isEmpty();
        assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                "csrfuser@email.com", LocalDateTime.now())).isEqualTo(1);
        mockMvc.perform(loginRequest("csrfuser")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[confirmEmail] Should Verify The Address And Consume Its Token - When Token Is Valid")
    void shouldVerifyTheAddressAndConsumeItsTokenWhenTokenIsValid() throws Exception {
        MvcResult registration = mockMvc.perform(registerRequest("confirmuser", "confirmuser@email.com"))
                .andExpect(status().isAccepted())
                .andReturn();
        String token = emailConfirmationSender.tokenFor("confirmuser@email.com");

        mockMvc.perform(confirmEmailRequest(token)).andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmailIgnoreCase("confirmuser@email.com")).isEmpty();

        mockMvc.perform(confirmEmailRequest(token)
                        .cookie(registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andExpect(cookie().maxAge(CookieUtil.REGISTRATION_PROOF_COOKIE, 0));

        assertThat(userRepository.findByEmailIgnoreCase("confirmuser@email.com").orElseThrow().getIsEmailVerified())
                .isTrue();
        mockMvc.perform(loginRequest("confirmuser")).andExpect(status().isOk());
        mockMvc.perform(confirmEmailRequest(token)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[register] Should Keep Browser Attempts Separate - When The Same Email Starts Registration Twice")
    void shouldKeepBrowserAttemptsSeparateWhenTheSameEmailStartsRegistrationTwice() throws Exception {
        MvcResult attackerRegistration = registerAndRememberProof("preclaim", "owner@email.com");
        String attackerToken = emailConfirmationSender.tokenFor("owner@email.com");

        MvcResult ownerRegistration = registerAndRememberProof("emailowner", "owner@email.com");
        String ownerToken = emailConfirmationSender.tokenFor("owner@email.com");
        assertThat(ownerToken).isNotEqualTo(attackerToken);
        assertThat(userRepository.findByEmailIgnoreCase("owner@email.com")).isEmpty();
        assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                "owner@email.com", LocalDateTime.now())).isEqualTo(2);

        mockMvc.perform(confirmEmailRequest(attackerToken)
                        .cookie(ownerRegistration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(confirmEmailRequest(attackerToken)
                        .cookie(attackerRegistration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isNoContent());
        assertThat(userRepository.findByEmailIgnoreCase("owner@email.com").orElseThrow().getIsEmailVerified())
                .isTrue();
        mockMvc.perform(confirmEmailRequest(ownerToken)
                        .cookie(ownerRegistration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isBadRequest());
        assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                "owner@email.com", LocalDateTime.now())).isZero();
    }

    @Test
    @DisplayName("[confirmEmail] Should Serialize Confirmation With New Attempts - When The Email Is Locked")
    void shouldWaitForEmailLockBeforeConfirmingRegistration() throws Exception {
        String email = "serialized-confirmation@email.com";
        MvcResult registration = registerAndRememberProof("serializedconfirmation", email);
        String token = emailConfirmationSender.tokenFor(email);
        Cookie proof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);

        try (Connection lockConnection = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            lockConnection.setAutoCommit(true);
            try (PreparedStatement lock = lockConnection.prepareStatement(
                    "SELECT pg_advisory_lock(hashtextextended(LOWER(?), 0))")) {
                lock.setString(1, email);
                lock.execute();
            }

            Future<Integer> confirmation = executor.submit(() -> mockMvc.perform(
                            confirmEmailRequest(token).cookie(proof))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            boolean waitedForEmailLock = awaitAdvisoryLockWaitOrCompletion(confirmation, email);
            assertThat(waitedForEmailLock)
                    .as("confirmation must serialize through the per-email registration lock")
                    .isTrue();

            try (PreparedStatement unlock = lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtextextended(LOWER(?), 0))")) {
                unlock.setString(1, email);
                unlock.execute();
            }

            assertThat(confirmation.get(10, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(userRepository.findByEmailIgnoreCase(email)).isPresent();
        }
    }

    @Test
    @DisplayName("[register] Should Recheck Email Under Its Lock - When It Becomes Active After Preflight")
    void shouldRejectRegistrationWhenEmailBecomesActiveAfterPreflight() throws Exception {
        String email = "active-during-register@email.com";

        try (Connection lockConnection = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            lockConnection.setAutoCommit(true);
            try (PreparedStatement lock = lockConnection.prepareStatement(
                    "SELECT pg_advisory_lock(hashtextextended(LOWER(?), 0))")) {
                lock.setString(1, email);
                lock.execute();
            }

            Future<Integer> registration = executor.submit(() -> mockMvc.perform(
                            registerRequest("racingregistration", email))
                    .andReturn()
                    .getResponse()
                    .getStatus());

            boolean waitedForEmailLock = awaitAdvisoryLockWaitOrCompletion(registration, email);
            assertThat(waitedForEmailLock)
                    .as("registration must reach the same per-email lock after its initial preflight")
                    .isTrue();

            LocalDateTime now = LocalDateTime.now();
            userRepository.saveAndFlush(User.builder()
                    .username("existing-confirmed-user")
                    .name("Existing confirmed user")
                    .email(email)
                    .password(passwordEncoder.encode("Password123"))
                    .isEmailVerified(true)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            try (PreparedStatement unlock = lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtextextended(LOWER(?), 0))")) {
                unlock.setString(1, email);
                unlock.execute();
            }

            assertThat(registration.get(10, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                    email, LocalDateTime.now())).isZero();
        }
    }

    @Test
    @DisplayName("[register] Should Bound Separate Attempts - When The Same Email Starts More Than Three Registrations")
    void shouldBoundSeparateAttemptsWhenTheSameEmailStartsMoreThanThreeRegistrations() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(registerRequest("attempt" + i, "limited@email.com"))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(registerRequest("attempt4", "limited@email.com").with(req -> {
                    req.setRemoteAddr("10.0.0.99");
                    return req;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        "Too many pending registration attempts for this email. Try again later."));

        assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                "limited@email.com", LocalDateTime.now())).isEqualTo(3);
    }

    @Test
    @DisplayName("[register] Should Enforce The Three Attempt Limit Concurrently - When Four Browsers Use The Same Email")
    void shouldEnforceThreeAttemptLimitConcurrentlyWhenFourBrowsersUseTheSameEmail() throws Exception {
        int requestCount = 4;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            List<java.util.concurrent.Future<Integer>> responses = IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent registration start was not released");
                        }
                        return mockMvc.perform(registerRequest("parallel" + index, "parallel@email.com")
                                        .with(request -> {
                                            request.setRemoteAddr("10.0.1." + (index + 1));
                                            return request;
                                        }))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = responses.stream()
                    .map(response -> {
                        try {
                            return response.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new IllegalStateException("Concurrent registration request failed", exception);
                        }
                    })
                    .toList();

            assertThat(statuses).containsExactlyInAnyOrder(202, 202, 202, 429);
            assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                    "parallel@email.com", LocalDateTime.now())).isEqualTo(3);
        } finally {
            start.countDown();
        }
    }

    @Test
    @DisplayName("[confirmEmail/resend] Should Resend Only With The Registration Browser Proof")
    void shouldResendRegistrationTokenOnlyWithTheBrowserProof() throws Exception {
        MvcResult registration = registerAndRememberProof("resendproofuser", "resendproofuser@email.com");
        Cookie proof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        String originalToken = emailConfirmationSender.tokenFor("resendproofuser@email.com");

        mockMvc.perform(resendEmailConfirmationRequest("resendproofuser@email.com"))
                .andExpect(status().isNoContent());
        assertThat(emailConfirmationSender.tokenFor("resendproofuser@email.com")).isEqualTo(originalToken);

        MvcResult resendResult = mockMvc.perform(
                        resendEmailConfirmationRequest("resendproofuser@email.com").cookie(proof))
                .andExpect(status().isNoContent())
                .andReturn();
        String resentToken = emailConfirmationSender.tokenFor("resendproofuser@email.com");
        assertThat(resentToken).isNotEqualTo(originalToken);
        Cookie renewedProof = resendResult.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        assertThat(renewedProof).isNotNull();
        assertThat(renewedProof.getValue()).isEqualTo(proof.getValue());
        assertThat(renewedProof.getMaxAge()).isEqualTo(30 * 60);

        mockMvc.perform(confirmEmailRequest(resentToken).cookie(renewedProof)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[register] Should Not Rotate Csrf Token - When A Stale Cookie Was Already Present")
    void shouldNotRotateCsrfTokenWhenAStaleCookieWasAlreadyPresent() throws Exception {
        MvcResult anonymousResult = mockMvc.perform(get("/protected-test-route"))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andReturn();

        Cookie staleCsrfCookie = anonymousResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(staleCsrfCookie).isNotNull();

        MvcResult registerResult = mockMvc.perform(registerRequest("rotateduser", "rotateduser@email.com")
                        .cookie(staleCsrfCookie))
                .andExpect(status().isAccepted())
                .andReturn();

        Cookie freshCsrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(freshCsrfCookie).isNull();
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
        registerAndRememberProof("duplicateuser", "first@email.com");

        MvcResult second = mockMvc.perform(registerRequest("duplicateuser", "second@email.com"))
                .andExpect(status().isAccepted())
                .andReturn();
        String secondToken = emailConfirmationSender.tokenFor("second@email.com");
        confirmRegisteredEmail("first@email.com");
        mockMvc.perform(confirmEmailRequest(secondToken)
                        .cookie(second.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[register] Should Return Conflict With Username Message - When Username Is Already Taken With Different Case")
    void shouldReturnConflictWithUsernameMessageWhenUsernameIsAlreadyTakenWithDifferentCase() throws Exception {
        registerAndRememberProof("CaseUser", "casefirst@email.com");

        MvcResult second = mockMvc.perform(registerRequest("caseuser", "casesecond@email.com"))
                .andExpect(status().isAccepted())
                .andReturn();
        String secondToken = emailConfirmationSender.tokenFor("casesecond@email.com");
        confirmRegisteredEmail("casefirst@email.com");
        mockMvc.perform(confirmEmailRequest(secondToken)
                        .cookie(second.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[register] Should Return Conflict With Email Message - When Email Is Already Verified")
    void shouldReturnConflictWithEmailMessageWhenEmailIsAlreadyVerified() throws Exception {
        registerAndRememberProof("firstuser", "duplicate@email.com");
        confirmRegisteredEmail("duplicate@email.com");

        mockMvc.perform(registerRequest("seconduser", "duplicate@email.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already in use"));
    }

    @Test
    @DisplayName("[register] Should Return Conflict - When Already Authenticated")
    void shouldReturnConflictWhenAlreadyAuthenticated() throws Exception {
        MvcResult loginResult = registerConfirmAndLogin("alreadyauthuser", "alreadyauthuser@email.com");
        Cookie accessTokenCookie = loginResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
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
                    .andExpect(status().isAccepted());
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
                .andExpect(status().isAccepted());
        }
        mockMvc.perform(registerRequest("ipthrottleuser10", "ipthrottleuser10@email.com"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(registerRequest("ipthrottleuser11", "ipthrottleuser11@email.com").with(req -> {
                    req.setRemoteAddr("10.0.0.99");
                    return req;
                }))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("[login] Should Return Conflict - When Already Authenticated")
    void shouldReturnConflictWhenLoggingInWhileAlreadyAuthenticated() throws Exception {
        MvcResult loginResult = registerConfirmAndLogin("otherloginuser", "otherloginuser@email.com");
        Cookie accessTokenCookie = loginResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(loginRequest("alreadyauthloginuser").cookie(accessTokenCookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already authenticated"));
    }

    @Test
    @DisplayName("[login] Should Return UserResponseDTO And Emit Access, Refresh And Csrf Cookies - When Credentials Are Valid")
    void shouldReturnUserResponseDtoAndEmitCookiesWhenCredentialsAreValid() throws Exception {
        registerAndRememberProof("loginuser", "loginuser@email.com");
        confirmRegisteredEmail("loginuser@email.com");

        mockMvc.perform(loginRequest("loginuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("loginuser"))
                .andExpect(cookie().exists(CookieUtil.ACCESS_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    @Test
    @DisplayName("[login] Should Reject The Session - When Password Changes After Credential Validation")
    void shouldRejectTheSessionWhenPasswordChangesAfterCredentialValidation() throws Exception {
        String email = "loginversionrace@email.com";
        registerAndRememberProof("loginversionrace", email);
        confirmRegisteredEmail(email);

        doAnswer(invocation -> {
            Object authenticatedUser = invocation.callRealMethod();
            User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            user.setPassword(passwordEncoder.encode("ChangedPassword456"));
            user.setAuthVersion(user.getAuthVersion() + 1);
            userRepository.saveAndFlush(user);
            return authenticatedUser;
        }).when(userService).loginForAuthentication(any());

        MvcResult login = mockMvc.perform(loginRequest("loginversionrace"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie accessToken = login.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie refreshToken = login.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        assertThat(refreshToken).isNotNull();

        mockMvc.perform(get("/users/me").cookie(accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/refresh").cookie(refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[oauthLogin] Should Reject The Session - When Password Changes After Account Lookup")
    void shouldRejectOauthSessionWhenPasswordChangesAfterAccountLookup() throws Exception {
        String email = "oauthversionrace@email.com";
        registerAndRememberProof("oauthversionrace", email);
        confirmRegisteredEmail(email);
        when(googleTokenVerifier.verify("oauth-version-race-token")).thenReturn(email);

        doAnswer(invocation -> {
            Object authenticatedUser = invocation.callRealMethod();
            User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
            user.setPassword(passwordEncoder.encode("ChangedPassword456"));
            user.setAuthVersion(user.getAuthVersion() + 1);
            userRepository.saveAndFlush(user);
            return authenticatedUser;
        }).when(userService).findByEmailForAuthentication(email);

        MvcResult oauthLogin = mockMvc.perform(oauthLoginRequest("google", "oauth-version-race-token"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie accessToken = oauthLogin.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie refreshToken = oauthLogin.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        assertThat(refreshToken).isNotNull();

        mockMvc.perform(get("/users/me").cookie(accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/refresh").cookie(refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[login] Should Return Unauthorized - When Password Is Incorrect")
    void shouldReturnUnauthorizedWhenPasswordIsIncorrect() throws Exception {
        registerAndRememberProof("wrongpassuser", "wrongpassuser@email.com");
        confirmRegisteredEmail("wrongpassuser@email.com");

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
        registerAndRememberProof("emailowner", "collision@email.com");
        confirmRegisteredEmail("collision@email.com");
        LocalDateTime now = LocalDateTime.now();
        userRepository.saveAndFlush(User.builder()
                .username("collision@email.com")
                .name("Legacy username owner")
                .email("usernameowner@email.com")
                .password(passwordEncoder.encode("Password123"))
                .isEmailVerified(true)
                .createdAt(now)
                .updatedAt(now)
                .build());

        mockMvc.perform(loginRequest("collision@email.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("collision@email.com"));
    }

    @Test
    @DisplayName("[login] Should Return TooManyRequests - When Failed Attempts Reach The Configured Max")
    void shouldReturnTooManyRequestsWhenFailedAttemptsReachTheConfiguredMax() throws Exception {
        registerAndRememberProof("ratelimituser", "ratelimituser@email.com");
        confirmRegisteredEmail("ratelimituser@email.com");

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
        registerAndRememberProof("ratelimituserA", "ratelimituserA@email.com");
        registerAndRememberProof("ratelimituserB", "ratelimituserB@email.com");
        confirmRegisteredEmail("ratelimituserA@email.com");
        confirmRegisteredEmail("ratelimituserB@email.com");

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
        registerAndRememberProof("ratelimitipuser", "ratelimitipuser@email.com");
        confirmRegisteredEmail("ratelimitipuser@email.com");

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
        MvcResult registration = registerAndRememberProof("oauthuser", "oauthuser@email.com");

        when(googleTokenVerifier.verify("valid-google-token")).thenReturn("oauthuser@email.com");

        mockMvc.perform(oauthLoginRequest("google", "valid-google-token"))
                .andExpect(status().isNotFound());

        mockMvc.perform(confirmEmailRequest(emailConfirmationSender.tokenFor("oauthuser@email.com"))
                        .cookie(registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE)))
                .andExpect(status().isNoContent());

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
        MvcResult loginResult = registerConfirmAndLogin("alreadyauthoauthuser", "alreadyauthoauthuser@email.com");
        Cookie accessTokenCookie = loginResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
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
        MvcResult loginResult = registerConfirmAndLogin("reuseuser", "reuseuser@email.com");
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
        MvcResult loginResult = registerConfirmAndLogin("flowuser", "flowuser@email.com");
        assertThat(loginResult.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE)).isNotNull();

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
        MvcResult deviceALogin = registerConfirmAndLogin("multideviceuser", "multideviceuser@email.com");
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
        MvcResult loginResult = registerConfirmAndLogin("accesstokeninvalidateuser", "accesstokeninvalidateuser@email.com");
        Cookie accessTokenCookie = loginResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = loginResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
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
        MvcResult userALogin = registerConfirmAndLogin("logoutalluserA", "logoutalluserA@email.com");
        Cookie userAAccessCookie = userALogin.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie userACsrfCookie = userALogin.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(userAAccessCookie).isNotNull();
        assertThat(userACsrfCookie).isNotNull();

        MvcResult userBLogin = registerConfirmAndLogin("logoutalluserB", "logoutalluserB@email.com");
        Cookie userBRefreshCookie = userBLogin.getResponse().getCookie(CookieUtil.REFRESH_TOKEN_COOKIE);
        assertThat(userBRefreshCookie).isNotNull();

        mockMvc.perform(logoutAllRequest(userAAccessCookie, userACsrfCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/auth/refresh").cookie(userBRefreshCookie))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("[logoutAll] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForLogoutAll() throws Exception {
        MvcResult anonymousResult = mockMvc.perform(get("/protected-test-route"))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andReturn();
        Cookie csrfCookie = anonymousResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(post("/auth/logout-all")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[logoutAll] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForLogoutAll() throws Exception {
        MvcResult loginResult = registerConfirmAndLogin("nocsrflogoutalluser", "nocsrflogoutalluser@email.com");
        Cookie accessTokenCookie = loginResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(post("/auth/logout-all").cookie(accessTokenCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[logoutAll] Should Clear Access, Refresh And Csrf Cookies - When Called")
    void shouldClearAccessRefreshAndCsrfCookiesWhenLogoutAllCalled() throws Exception {
        MvcResult loginResult = registerConfirmAndLogin("clearcookieslogoutalluser", "clearcookieslogoutalluser@email.com");
        Cookie accessTokenCookie = loginResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = loginResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
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

    private void confirmRegisteredEmail(String email) throws Exception {
        String token = emailConfirmationSender.tokenFor(email);
        Cookie proof = registrationProofCookies.get(email);
        mockMvc.perform(confirmEmailRequest(token).cookie(proof)).andExpect(status().isNoContent());
    }

    private MvcResult registerAndRememberProof(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username, email))
                .andExpect(status().isAccepted())
                .andReturn();
        registrationProofCookies.put(email, result.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE));
        return result;
    }

    private boolean awaitAdvisoryLockWaitOrCompletion(Future<?> operation, String email) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) {
                return false;
            }
            Boolean waiting = jdbcTemplate.queryForObject("""
                    WITH requested_lock AS (
                        SELECT hashtextextended(LOWER(?), 0) AS lock_key
                    )
                            SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks, requested_lock
                        WHERE locktype = 'advisory'
                          AND granted = FALSE
                          AND classid = ((lock_key >> 32) & 4294967295)::oid
                          AND objid = (lock_key & 4294967295)::oid
                          AND objsubid = 1
                    )
                    """, Boolean.class, email);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Operation neither waited for the email lock nor completed");
    }

    private MvcResult registerConfirmAndLogin(String username, String email) throws Exception {
        registerAndRememberProof(username, email);
        confirmRegisteredEmail(email);
        return mockMvc.perform(loginRequest(username)).andExpect(status().isOk()).andReturn();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder registerRequest(String username, String email) {
        String body = """
                {
                    "username": "%s",
                    "name": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": true
                }
                """.formatted(username, username, email);

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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmEmailRequest(String token) {
        String body = "{\"token\":\"%s\"}".formatted(token);
        return post("/auth/confirm-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resendEmailConfirmationRequest(String email) {
        return post("/auth/confirm-email/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}");
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
