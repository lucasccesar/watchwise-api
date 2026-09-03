package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserControllerIntegrationTest {

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RequestThrottler requestThrottler;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Access Token Cookie Is Valid")
    void shouldReturnUserResponseDtoWhenAccessTokenCookieIsValid() throws Exception {
        Cookie accessTokenCookie = registerAndGetAccessToken("meuser", "meuser@email.com");

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("meuser"))
                .andExpect(jsonPath("$.email").value("meuser@email.com"));
    }

    @Test
    @DisplayName("[getCurrentUser] Should Include Watch Time Stats - When Diary Has A Movie Entry With RuntimeMinutes And Genres")
    void shouldIncludeWatchTimeStatsWhenDiaryHasAMovieEntryWithRuntimeMinutesAndGenres() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("statsuser", "statsuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();
        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        User statsUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("statsuser", "statsuser")
                .orElseThrow();

        seedMovieWatch(statsUser, "550", 139, List.of("Drama"), LocalDate.now());

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutesWatched").value(139))
                .andExpect(jsonPath("$.minutesWatchedLast30Days").value(139))
                .andExpect(jsonPath("$.genreCountsMovies[0].genre").value("Drama"))
                .andExpect(jsonPath("$.genreCountsMovies[0].count").value(1))
                .andExpect(jsonPath("$.genreCountsSeries").isEmpty());
    }

    @Test
    @DisplayName("[getCurrentUser] Should Include Followers And Following Counts - When User Has Accepted Followers")
    void shouldIncludeFollowersAndFollowingCountsWhenUserHasAcceptedFollowers() throws Exception {
        Cookie accessTokenCookie = registerAndGetAccessToken("followcountuser", "followcountuser@email.com");
        User followCountUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("followcountuser", "followcountuser")
                .orElseThrow();
        User follower = userRepository.save(buildUser("followerof", "followerof@email.com"));
        User followed = userRepository.save(buildUser("followedby", "followedby@email.com"));
        persistFollow(follower, followCountUser, FollowStatus.ACCEPTED);
        persistFollow(followCountUser, followed, FollowStatus.ACCEPTED);

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followersCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(1));
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGetCurrentUser() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Authenticated User - When Request Is Valid")
    void shouldUpdateAuthenticatedUserWhenRequestIsValid() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("johndoe", "johndoe@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("johndoe"))
                .andExpect(jsonPath("$.description").value("Updated bio"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Banner - When Request Is Valid")
    void shouldUpdateBannerWhenRequestIsValid() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("bannerupdateuser", "bannerupdateuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"banner\": \"https://picture.com/banner.png\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banner").value("https://picture.com/banner.png"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Accept The Same Csrf Token Twice - When Two Authenticated Requests Are Made In A Row")
    void shouldAcceptTheSameCsrfTokenTwiceWhenTwoAuthenticatedRequestsAreMadeInARow() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("repeatuser", "repeatuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"First update\" }"))
                .andExpect(status().isOk());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Second update\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Second update"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Only The Authenticated User - When Another User Exists")
    void shouldUpdateOnlyTheAuthenticatedUserWhenAnotherUserExists() throws Exception {
        mockMvc.perform(registerRequest("userone", "userone@email.com")).andExpect(status().isCreated());

        MvcResult registerResultTwo = mockMvc.perform(registerRequest("usertwo", "usertwo@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookieTwo = registerResultTwo.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookieTwo = registerResultTwo.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookieTwo, csrfCookieTwo, "{ \"description\": \"User two bio\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("usertwo"))
                .andExpect(jsonPath("$.description").value("User two bio"));

        User userOne = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("userone", "userone")
                .orElseThrow();
        assertThat(userOne.getDescription()).isNull();
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocookieuser", "nocookieuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(patch("/users/me")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When Account Was Deleted After Token Was Issued")
    void shouldReturnUnauthorizedWhenAccountWasDeletedAfterTokenWasIssued() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("stalepatchuser", "stalepatchuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Conflict - When Updating Username To One Already Taken By Another Real User")
    void shouldReturnConflictWhenUpdatingUsernameToOneAlreadyTakenByAnotherRealUser() throws Exception {
        mockMvc.perform(registerRequest("takenusername", "takenusername@email.com"))
                .andExpect(status().isCreated());

        MvcResult registerResult = mockMvc.perform(registerRequest("conflictuser", "conflictuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"username\": \"takenusername\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Conflict - When Updating Username To One Already Taken With Different Case")
    void shouldReturnConflictWhenUpdatingUsernameToOneAlreadyTakenWithDifferentCase() throws Exception {
        mockMvc.perform(registerRequest("CaseTakenUser", "casetakenuser@email.com"))
                .andExpect(status().isCreated());

        MvcResult registerResult = mockMvc.perform(registerRequest("caseconflictuser", "caseconflictuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"username\": \"casetakenuser\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return BadRequest - When Email Format Is Invalid")
    void shouldReturnBadRequestWhenEmailFormatIsInvalid() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("invalidemailuser", "invalidemailuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"email\": \"not-an-email\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Password - When CurrentPassword Is Correct")
    void shouldUpdatePasswordWhenCurrentPasswordIsCorrect() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("passwordchangeuser", "passwordchangeuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk());

        User updatedUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("passwordchangeuser", "passwordchangeuser")
                .orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword123", updatedUser.getPassword())).isTrue();
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Invalidate The Access Token Already Issued - When Password Changes")
    void shouldInvalidateTheAccessTokenAlreadyIssuedWhenPasswordChanges() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("pwchangeinvalidateuser", "pwchangeinvalidateuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return BadRequest - When Changing Password Without CurrentPassword")
    void shouldReturnBadRequestWhenChangingPasswordWithoutCurrentPassword() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nopwconfirmuser", "nopwconfirmuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"NewPassword123\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("currentPassword must be provided to change password or email"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When Changing Password With Wrong CurrentPassword")
    void shouldReturnUnauthorizedWhenChangingPasswordWithWrongCurrentPassword() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("wrongpwconfirmuser", "wrongpwconfirmuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"WrongPassword123\" }"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password"));

        User untouchedUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("wrongpwconfirmuser", "wrongpwconfirmuser")
                .orElseThrow();
        assertThat(passwordEncoder.matches("Password123", untouchedUser.getPassword())).isTrue();
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Email - When CurrentPassword Is Correct")
    void shouldUpdateEmailWhenCurrentPasswordIsCorrect() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("emailchangeuser", "emailchangeuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"email\": \"newemail@email.com\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("newemail@email.com"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return BadRequest - When Changing Email Without CurrentPassword")
    void shouldReturnBadRequestWhenChangingEmailWithoutCurrentPassword() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("noemailconfirmuser", "noemailconfirmuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"email\": \"newemail2@email.com\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("currentPassword must be provided to change password or email"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When Changing Email With Wrong CurrentPassword")
    void shouldReturnUnauthorizedWhenChangingEmailWithWrongCurrentPassword() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("wrongemailconfirmuser", "wrongemailconfirmuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"email\": \"newemail3@email.com\", \"currentPassword\": \"WrongPassword123\" }"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password"));

        User untouchedUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("wrongemailconfirmuser", "wrongemailconfirmuser")
                .orElseThrow();
        assertThat(untouchedUser.getEmail()).isEqualTo("wrongemailconfirmuser@email.com");
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return TooManyRequests - When Wrong CurrentPassword Attempts Reach The Configured Max")
    void shouldReturnTooManyRequestsWhenPatchWrongCurrentPasswordAttemptsReachTheConfiguredMax() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("patchlockoutuser", "patchlockoutuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                            "{ \"password\": \"NewPassword123\", \"currentPassword\": \"WrongPassword123\" }"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many attempts. Try again later."));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Not Reset The Lockout Counter - When An Unrelated Successful Edit Happens Between Failed Password Attempts")
    void shouldNotResetTheLockoutCounterWhenAnUnrelatedSuccessfulEditHappensBetweenFailedPasswordAttempts() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("interleaveuser", "interleaveuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                            "{ \"password\": \"NewPassword123\", \"currentPassword\": \"WrongPassword123\" }"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"description\": \"Harmless bio edit\" }"))
                .andExpect(status().isOk());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"WrongPassword123\" }"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Not Reset The Lockout Counter - When The Same Current Email Is Resent Between Failed Password Attempts")
    void shouldNotResetTheLockoutCounterWhenTheSameCurrentEmailIsResentBetweenFailedPasswordAttempts() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("resendemailuser", "resendemailuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                            "{ \"password\": \"NewPassword123\", \"currentPassword\": \"WrongPassword123\" }"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                            "{ \"email\": \"resendemailuser@email.com\" }"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"WrongPassword123\" }"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"password\": \"NewPassword123\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissing() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocsrfuser", "nocsrfuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();

        mockMvc.perform(patch("/users/me")
                        .cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"description\": \"Updated bio\" }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Delete Account And Clear Cookies - When Password Matches")
    void shouldDeleteAccountAndClearCookiesWhenPasswordMatches() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("deleteuser", "deleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(CookieUtil.ACCESS_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.REFRESH_TOKEN_COOKIE, 0))
                .andExpect(cookie().maxAge(CookieUtil.CSRF_TOKEN_COOKIE, 0));

        Optional<User> deletedUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("deleteuser", "deleteuser");
        assertThat(deletedUser).isEmpty();
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized And Keep Account - When Password Does Not Match")
    void shouldReturnUnauthorizedAndKeepAccountWhenPasswordDoesNotMatch() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("keepuser", "keepuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"WrongPassword123\" }"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid password"));

        Optional<User> keptUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("keepuser", "keepuser");
        assertThat(keptUser).isPresent();
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return TooManyRequests - When Wrong Password Attempts Reach The Configured Max")
    void shouldReturnTooManyRequestsWhenDeleteWrongPasswordAttemptsReachTheConfiguredMax() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("deletelockoutuser", "deletelockoutuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"WrongPassword123\" }"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many attempts. Try again later."));

        Optional<User> stillExists = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("deletelockoutuser", "deletelockoutuser");
        assertThat(stillExists).isPresent();
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return BadRequest - When Password Is Blank")
    void shouldReturnBadRequestWhenPasswordIsBlank() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("blankdeleteuser", "blankdeleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForDeleteCurrentUser() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("nocookiedeleteuser", "nocookiedeleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(csrfCookie).isNotNull();

        mockMvc.perform(delete("/users/me")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"password\": \"Password123\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized - When Retried With The Access Token Of An Already-Deleted Account")
    void shouldReturnUnauthorizedWhenRetriedWithTheAccessTokenOfAnAlreadyDeletedAccount() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("doubledeleteuser", "doubledeleteuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return Unauthorized - When Called With The Access Token Of An Already-Deleted Account")
    void shouldReturnUnauthorizedWhenCalledWithTheAccessTokenOfAnAlreadyDeletedAccount() throws Exception {
        MvcResult registerResult = mockMvc.perform(registerRequest("deletedtokenuser", "deletedtokenuser@email.com"))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForDeleteCurrentUser() throws Exception {
        Cookie accessTokenCookie = registerAndGetAccessToken("nocsrfdeleteuser", "nocsrfdeleteuser@email.com");

        mockMvc.perform(delete("/users/me")
                        .cookie(accessTokenCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"password\": \"Password123\" }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[getUserById] Should Return PublicUserProfileDTO - When User Exists And Profile Is Public")
    void shouldReturnPublicUserDtoWhenUserExistsAndProfileIsPublic() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerpublic", "viewerpublic@email.com");

        mockMvc.perform(registerRequest("targetpublic", "targetpublic@email.com"))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetpublic", "targetpublic")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetUser.getId().toString()))
                .andExpect(jsonPath("$.username").value("targetpublic"))
                .andExpect(jsonPath("$.isProfilePublic").value(true))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.totalMinutesWatched").value(0))
                .andExpect(jsonPath("$.genreCountsMovies").isEmpty())
                .andExpect(jsonPath("$.genreCountsSeries").isEmpty());
    }

    @Test
    @DisplayName("[getUserById] Should Include Watch Time Stats - When Target User's Diary Has An Episode Entry")
    void shouldIncludeWatchTimeStatsWhenTargetUsersDiaryHasAnEpisodeEntry() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerstats", "viewerstats@email.com");

        mockMvc.perform(registerRequest("targetstats", "targetstats@email.com"))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetstats", "targetstats")
                .orElseThrow();

        Content series = contentRepository.save(Content.builder()
                .tmdbId("1399").type(ContentType.SERIES).genres(List.of("Drama", "Action"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        Content episode = contentRepository.save(Content.builder()
                .seriesTmdbId("1399").seasonNumber(1).episodeNumber(1).type(ContentType.EPISODE).runtimeMinutes(55)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        diaryEntryRepository.save(DiaryEntry.builder()
                .user(targetUser).content(episode).watchNumber(1).watchedDate(LocalDate.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        contentRepository.saveAndFlush(series);

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutesWatched").value(55))
                .andExpect(jsonPath("$.genreCountsMovies").isEmpty())
                .andExpect(jsonPath("$.genreCountsSeries[*].genre", org.hamcrest.Matchers.containsInAnyOrder("Drama", "Action")))
                .andExpect(jsonPath("$.genreCountsSeries[*].count", org.hamcrest.Matchers.containsInAnyOrder(1, 1)));
    }

    @Test
    @DisplayName("[getUserById] Should Return Forbidden - When Target Profile Is Private")
    void shouldReturnForbiddenWhenTargetProfileIsPrivate() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerprivate", "viewerprivate@email.com");

        mockMvc.perform(registerRequest("targetprivate", "targetprivate@email.com", false))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetprivate", "targetprivate")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getUserById] Should Return NotFound - When User Does Not Exist")
    void shouldReturnNotFoundWhenUserDoesNotExist() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewernotfound", "viewernotfound@email.com");

        mockMvc.perform(get("/users/" + UUID.randomUUID()).cookie(viewerAccessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getUserById] Should Return BadRequest With Expected Type - When UserId Path Variable Is Not A Valid Uuid")
    void shouldReturnBadRequestWithExpectedTypeWhenUserIdPathVariableIsNotAValidUuid() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerbaduuid", "viewerbaduuid@email.com");

        mockMvc.perform(get("/users/not-a-uuid").cookie(viewerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'not-a-uuid' for parameter 'userId'. Expected type: UUID"));
    }

    @Test
    @DisplayName("[getUserById] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGetUserById() throws Exception {
        mockMvc.perform(registerRequest("targetnoauth", "targetnoauth@email.com"))
                .andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetnoauth", "targetnoauth")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Matching Users - When Username Prefix Matches")
    void shouldReturnMatchingUsersWhenUsernamePrefixMatches() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("searchviewer", "searchviewer@email.com");
        mockMvc.perform(registerRequest("searchuser1", "searchuser1@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("searchuser2", "searchuser2@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("unrelated", "unrelated@email.com")).andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "searchuser").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].username").value("searchuser1"))
                .andExpect(jsonPath("$.content[1].username").value("searchuser2"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Include Private Profiles In Results - When Username Matches")
    void shouldIncludePrivateProfilesInResultsWhenUsernameMatches() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("privatesearchviewer", "privatesearchviewer@email.com");
        mockMvc.perform(registerRequest("privatesearchtarget", "privatesearchtarget@email.com", false))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "privatesearchtarget").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("privatesearchtarget"))
                .andExpect(jsonPath("$.content[0].isProfilePublic").value(false));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Respect Size Parameter - When Provided")
    void shouldRespectSizeParameterWhenProvided() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("sizeviewer", "sizeviewer@email.com");
        mockMvc.perform(registerRequest("sizeuser1", "sizeuser1@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("sizeuser2", "sizeuser2@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("sizeuser3", "sizeuser3@email.com")).andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "sizeuser").param("size", "2").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Different Page Of Results - When Page Parameter Changes")
    void shouldReturnDifferentPageOfResultsWhenPageParameterChanges() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("pageviewer", "pageviewer@email.com");
        mockMvc.perform(registerRequest("pageuser1", "pageuser1@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("pageuser2", "pageuser2@email.com")).andExpect(status().isCreated());
        mockMvc.perform(registerRequest("pageuser3", "pageuser3@email.com")).andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "pageuser").param("page", "1").param("size", "1").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("pageuser1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/users").param("username", "pageuser").param("page", "2").param("size", "1").cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("pageuser2"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return BadRequest - When Username Is Missing")
    void shouldReturnBadRequestWhenUsernameIsMissing() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("nousernameviewer", "nousernameviewer@email.com");

        mockMvc.perform(get("/users").cookie(viewerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required parameter 'username' is missing"));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return BadRequest - When Username Is Blank")
    void shouldReturnBadRequestWhenUsernameIsBlank() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("blankusernameviewer", "blankusernameviewer@email.com");

        mockMvc.perform(get("/users").param("username", "").cookie(viewerAccessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username must be provided"));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForSearch() throws Exception {
        mockMvc.perform(registerRequest("noauthsearchtarget", "noauthsearchtarget@email.com"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/users").param("username", "noauthsearchtarget"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getUsersByUsername][getUserById] Should Return TooManyRequests - When Combined Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenCombinedProfileRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("scanviewer", "scanviewer@email.com");
        mockMvc.perform(registerRequest("scantarget", "scantarget@email.com")).andExpect(status().isCreated());
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("scantarget", "scantarget")
                .orElseThrow();

        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/users").param("username", "scantarget").cookie(viewerAccessToken))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[getUsersByUsername] Should Not Block A Different User - When Another User Is Rate Limited")
    void shouldNotBlockADifferentUserWhenAnotherUserIsRateLimitedForProfileScan() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("scanviewerA", "scanviewerA@email.com");
        Cookie otherViewerAccessToken = registerAndGetAccessToken("scanviewerB", "scanviewerB@email.com");

        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/users").param("username", "nomatch").cookie(viewerAccessToken))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/users").param("username", "nomatch").cookie(viewerAccessToken))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/users").param("username", "nomatch").cookie(otherViewerAccessToken))
                .andExpect(status().isOk());
    }

    private void seedMovieWatch(User user, String tmdbId, int runtimeMinutes, List<String> genres, LocalDate watchedDate) {
        Content movie = contentRepository.save(Content.builder()
                .tmdbId(tmdbId).type(ContentType.MOVIE).runtimeMinutes(runtimeMinutes).genres(genres)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        diaryEntryRepository.save(DiaryEntry.builder()
                .user(user).content(movie).watchNumber(1).watchedDate(watchedDate)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private User buildUser(String username, String email) {
        LocalDateTime now = LocalDateTime.now();
        return User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode("Password123"))
                .isProfilePublic(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void persistFollow(User follower, User followed, FollowStatus status) {
        followerRepository.save(Follower.builder()
                .follower(follower)
                .followed(followed)
                .status(status)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Cookie registerAndGetAccessToken(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username, email))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        return accessTokenCookie;
    }

    private MockHttpServletRequestBuilder patchMeRequest(Cookie accessTokenCookie, Cookie csrfCookie, String body) {
        return patch("/users/me")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder deleteMeRequest(Cookie accessTokenCookie, Cookie csrfCookie, String body) {
        return delete("/users/me")
                .cookie(accessTokenCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, String email) {
        return registerRequest(username, email, true);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, String email, boolean isProfilePublic) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, email, isProfilePublic);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
