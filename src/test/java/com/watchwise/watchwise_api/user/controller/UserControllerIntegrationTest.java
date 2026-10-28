package com.watchwise.watchwise_api.user.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.PendingRegistrationRepository;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.TestEmailConfirmationSender;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.entity.UserListVisibility;
import com.watchwise.watchwise_api.userlist.repository.UserListItemRepository;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TestEmailConfirmationSender emailConfirmationSender;

    @Autowired
    private RequestThrottler requestThrottler;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private WatchCompanionRepository watchCompanionRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserListRepository userListRepository;

    @Autowired
    private UserListItemRepository userListItemRepository;

    @BeforeEach
    void setUp() {
        userBlockRepository.deleteAll();
        userListItemRepository.deleteAll();
        userListRepository.deleteAll();
        watchCompanionRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        notificationRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
        emailConfirmationSender.clear();
    }

    @Test
    void passwordChangeRollsBackWhenClearingLockoutFails() throws Exception {
        assertAccountActionRollsBackWhenClearingLockoutFails(false);
    }

    @Test
    void accountDeletionRollsBackWhenClearingLockoutFails() throws Exception {
        assertAccountActionRollsBackWhenClearingLockoutFails(true);
    }

    private void assertAccountActionRollsBackWhenClearingLockoutFails(boolean deletion) throws Exception {
        MvcResult login = registerConfirmAndLogin("atomicaccount", "atomicaccount@example.test");
        User before = userRepository.findByUsernameIgnoreCase("atomicaccount").orElseThrow();
        var refreshBefore = jdbcTemplate.queryForList("SELECT id, revoked FROM refresh_tokens ORDER BY id");
        long attemptsBefore = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(request_count), 0) FROM shared_limiter_state WHERE kind = 'lockout'", Long.class);
        Cookie access = login.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrf = login.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_lockout_clear() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test lockout clear unavailable'; END $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_lockout_clear BEFORE DELETE ON shared_limiter_state
                FOR EACH ROW WHEN (OLD.kind = 'lockout') EXECUTE FUNCTION reject_lockout_clear()
                """);
        try {
            MockHttpServletRequestBuilder action = deletion
                    ? deleteMeRequest(access, csrf, "{\"password\":\"Password123\"}")
                    : patchMeRequest(access, csrf,
                            "{\"password\":\"NewPassword123\",\"currentPassword\":\"Password123\"}");
            mockMvc.perform(action)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(cookie().doesNotExist(CookieUtil.ACCESS_TOKEN_COOKIE))
                    .andExpect(cookie().doesNotExist(CookieUtil.REFRESH_TOKEN_COOKIE))
                    .andExpect(cookie().doesNotExist(CookieUtil.CSRF_TOKEN_COOKIE));
            assertThat(userRepository.findById(before.getId())).isPresent();
            User after = userRepository.findById(before.getId()).orElseThrow();
            assertThat(after.getPassword()).isEqualTo(before.getPassword());
            assertThat(after.getAuthVersion()).isEqualTo(before.getAuthVersion());
            assertThat(after.getSessionsInvalidatedAt()).isEqualTo(before.getSessionsInvalidatedAt());
            assertThat(jdbcTemplate.queryForList("SELECT id, revoked FROM refresh_tokens ORDER BY id"))
                    .isEqualTo(refreshBefore);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(request_count), 0) FROM shared_limiter_state WHERE kind = 'lockout'", Long.class))
                    .isEqualTo(attemptsBefore + 1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_lockout_clear ON shared_limiter_state");
            jdbcTemplate.execute("DROP FUNCTION reject_lockout_clear()");
        }
    }

    @Test
    @DisplayName("[register] Should Return BadRequest - When Display Name Is Missing")
    void shouldReturnBadRequestWhenDisplayNameIsMissing() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "missingname",
                                    "email": "missingname@email.com",
                                    "password": "Password123"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getCurrentUser] Should Return UserResponseDTO - When Access Token Cookie Is Valid")
    void shouldReturnUserResponseDtoWhenAccessTokenCookieIsValid() throws Exception {
        Cookie accessTokenCookie = registerAndGetAccessToken("meuser", "meuser@email.com");

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("meuser"))
                .andExpect(jsonPath("$.name").value("meuser"))
                .andExpect(jsonPath("$.email").value("meuser@email.com"));
    }

    @Test
    @DisplayName("[getCurrentUser] Should Include Watch Time Stats - When Diary Has A Movie Entry With RuntimeMinutes And Genres")
    void shouldIncludeWatchTimeStatsWhenDiaryHasAMovieEntryWithRuntimeMinutesAndGenres() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("statsuser", "statsuser@email.com");
        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        User statsUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("statsuser", "statsuser")
                .orElseThrow();

        seedMovieWatch(statsUser, "550", 139, List.of("Drama"), LocalDate.now());

        mockMvc.perform(get("/users/me").cookie(accessTokenCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").value(139))
                .andExpect(jsonPath("$.totalMinutesWatchedEpisodes").value(0))
                .andExpect(jsonPath("$.minutesWatchedMoviesLast30Days").value(139))
                .andExpect(jsonPath("$.minutesWatchedEpisodesLast30Days").value(0))
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
        MvcResult registerResult = registerConfirmAndLogin("johndoe", "johndoe@email.com");

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
    @DisplayName("[updateCurrentUser] Should Keep Current Email And Session Until Pending Email Is Confirmed")
    void shouldKeepCurrentEmailAndSessionUntilPendingEmailIsConfirmed() throws Exception {
        MvcResult login = registerConfirmAndLogin("pendingemailuser", "current@email.com");
        Cookie accessToken = login.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = login.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();

        mockMvc.perform(patchMeRequest(accessToken, csrfToken,
                        "{ \"email\": \"new@email.com\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("current@email.com"));

        User pendingChange = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(
                "pendingemailuser", "pendingemailuser").orElseThrow();
        assertThat(pendingChange.getEmail()).isEqualTo("current@email.com");
        assertThat(pendingChange.getPendingEmail()).isEqualTo("new@email.com");
        assertThat(pendingChange.getIsEmailVerified()).isTrue();
        mockMvc.perform(get("/users/me").cookie(accessToken)).andExpect(status().isOk());

        String firstConfirmationToken = emailConfirmationSender.tokenFor("new@email.com");
        mockMvc.perform(patchMeRequest(accessToken, csrfToken,
                        "{ \"email\": \"new@email.com\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("current@email.com"));
        String confirmationToken = emailConfirmationSender.tokenFor("new@email.com");
        assertThat(confirmationToken).isNotEqualTo(firstConfirmationToken);

        mockMvc.perform(confirmEmailRequest(confirmationToken)).andExpect(status().isNoContent());

        User confirmedChange = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(
                "pendingemailuser", "pendingemailuser").orElseThrow();
        assertThat(confirmedChange.getEmail()).isEqualTo("new@email.com");
        assertThat(confirmedChange.getPendingEmail()).isNull();
        assertThat(confirmedChange.getIsEmailVerified()).isTrue();
        mockMvc.perform(get("/users/me").cookie(accessToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest("current@email.com")).andExpect(status().isUnauthorized());
        mockMvc.perform(loginRequest("new@email.com")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[register] Should Reject Email Reserved By Another Account Pending Email Change")
    void shouldRejectEmailReservedByAnotherAccountPendingEmailChange() throws Exception {
        MvcResult login = registerConfirmAndLogin("pendingreservation", "reservation-current@email.com");
        Cookie accessToken = login.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = login.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessToken, csrfToken,
                        "{ \"email\": \"reserved@email.com\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk());

        mockMvc.perform(registerRequest("pendingcollision", "reserved@email.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Notify Pending Requesters - When Private Profile Becomes Public")
    void shouldNotifyPendingRequestersWhenPrivateProfileBecomesPublic() throws Exception {
        MvcResult ownerResult = registerConfirmAndLogin("publicowner", "publicowner@email.com", false);
        MvcResult requesterResult = registerConfirmAndLogin("publicrequester", "publicrequester@email.com");

        Cookie ownerAccessToken = ownerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie ownerCsrfToken = ownerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        User owner = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("publicowner", "publicowner")
                .orElseThrow();
        User requester = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("publicrequester", "publicrequester")
                .orElseThrow();
        persistFollow(requester, owner, FollowStatus.PENDING);

        mockMvc.perform(patchMeRequest(ownerAccessToken, ownerCsrfToken, "{ \"isProfilePublic\": true }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProfilePublic").value(true));

        Follower accepted = followerRepository.findByFollowerIdAndFollowedId(requester.getId(), owner.getId())
                .orElseThrow();
        assertThat(accepted.getStatus()).isEqualTo(FollowStatus.ACCEPTED);
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(requester.getId(), PageRequest.of(0, 10))
                .getContent()).extracting(Notification::getType)
                .containsExactly(NotificationType.FOLLOW_REQUEST_ACCEPTED);
        assertThat(requesterResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE)).isNotNull();
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Banner - When Request Is Valid")
    void shouldUpdateBannerWhenRequestIsValid() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("bannerupdateuser", "bannerupdateuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"banner\": \"https://picture.com/banner.png\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banner").value("https://picture.com/banner.png"));
    }

    @Test
    void shouldRemoveBannerAndKeepProfilePictureAcrossCurrentUserRead() throws Exception {
        MvcResult registration = registerConfirmAndLogin("bannerremoveuser", "bannerremoveuser@email.com");
        Cookie access = registration.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrf = registration.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        User owner = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("bannerremoveuser", "bannerremoveuser").orElseThrow();
        owner.setProfilePicture("https://picture.com/avatar.png");
        owner.setBanner("https://picture.com/banner.png");
        userRepository.saveAndFlush(owner);

        mockMvc.perform(deleteBannerRequest(access, csrf))
                .andExpect(status().isNoContent());
        mockMvc.perform(deleteBannerRequest(access, csrf))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/users/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.banner").doesNotExist())
                .andExpect(jsonPath("$.profilePicture").value("https://picture.com/avatar.png"));
    }

    @Test
    void shouldRequireAuthenticationToRemoveBanner() throws Exception {
        MvcResult registration = registerConfirmAndLogin("bannerunauthuser", "bannerunauthuser@email.com");
        Cookie csrf = registration.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        mockMvc.perform(delete("/users/me/banner").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRequireValidCsrfToRemoveBanner() throws Exception {
        MvcResult registration = registerConfirmAndLogin("bannercsrfuser", "bannercsrfuser@email.com");
        Cookie access = registration.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrf = registration.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        mockMvc.perform(delete("/users/me/banner").cookie(access, csrf).header("X-XSRF-TOKEN", "invalid"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder deleteBannerRequest(Cookie access, Cookie csrf) {
        return delete("/users/me/banner").cookie(access, csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Accept The Same Csrf Token Twice - When Two Authenticated Requests Are Made In A Row")
    void shouldAcceptTheSameCsrfTokenTwiceWhenTwoAuthenticatedRequestsAreMadeInARow() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("repeatuser", "repeatuser@email.com");

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
        registerConfirmAndLogin("userone", "userone@email.com");

        MvcResult registerResultTwo = registerConfirmAndLogin("usertwo", "usertwo@email.com");

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
        MvcResult anonymousResult = mockMvc.perform(get("/protected-test-route"))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andReturn();
        Cookie csrfCookie = anonymousResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
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
        MvcResult registerResult = registerConfirmAndLogin("stalepatchuser", "stalepatchuser@email.com");

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
        registerConfirmAndLogin("takenusername", "takenusername@email.com");

        MvcResult registerResult = registerConfirmAndLogin("conflictuser", "conflictuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"username\": \"takenusername\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Conflict - When Updating Username To One Already Taken With Different Case")
    void shouldReturnConflictWhenUpdatingUsernameToOneAlreadyTakenWithDifferentCase() throws Exception {
        registerConfirmAndLogin("CaseTakenUser", "casetakenuser@email.com");

        MvcResult registerResult = registerConfirmAndLogin("caseconflictuser", "caseconflictuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"username\": \"casetakenuser\" }"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already in use"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return BadRequest - When Email Format Is Invalid")
    void shouldReturnBadRequestWhenEmailFormatIsInvalid() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("invalidemailuser", "invalidemailuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"email\": \"not-an-email\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Update Password - When CurrentPassword Is Correct")
    void shouldUpdatePasswordWhenCurrentPasswordIsCorrect() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("passwordchangeuser", "passwordchangeuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("pwchangeinvalidateuser", "pwchangeinvalidateuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("nopwconfirmuser", "nopwconfirmuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"NewPassword123\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("currentPassword must be provided to change password or email"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When Changing Password With Wrong CurrentPassword")
    void shouldReturnUnauthorizedWhenChangingPasswordWithWrongCurrentPassword() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("wrongpwconfirmuser", "wrongpwconfirmuser@email.com");

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
    @DisplayName("[updateCurrentUser] Should Keep Current Email Until Confirmation - When CurrentPassword Is Correct")
    void shouldUpdateEmailWhenCurrentPasswordIsCorrect() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("emailchangeuser", "emailchangeuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie,
                        "{ \"email\": \"newemail@email.com\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("emailchangeuser@email.com"));

        User pendingChange = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(
                "emailchangeuser", "emailchangeuser").orElseThrow();
        assertThat(pendingChange.getEmail()).isEqualTo("emailchangeuser@email.com");
        assertThat(pendingChange.getPendingEmail()).isEqualTo("newemail@email.com");
        assertThat(emailConfirmationSender.tokenFor("newemail@email.com")).isNotBlank();
    }

    @Test
    @DisplayName("[confirmEmail] Should Remove Registration Attempts - When A Pending Email Becomes Active")
    void shouldRemovePendingRegistrationAttemptsWhenEmailChangeIsConfirmed() throws Exception {
        String targetEmail = "claimed-after-pending-registration@email.com";
        MvcResult ownerLogin = registerConfirmAndLogin("emailclaimowner", "emailclaimowner@email.com");
        Cookie accessToken = ownerLogin.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfToken = ownerLogin.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        MvcResult registration = mockMvc.perform(registerRequest("emailclaimattempt", targetEmail))
                .andExpect(status().isAccepted())
                .andReturn();
        String registrationToken = emailConfirmationSender.tokenFor(targetEmail);
        Cookie registrationProof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                targetEmail, LocalDateTime.now())).isEqualTo(1);

        mockMvc.perform(patchMeRequest(accessToken, csrfToken,
                        "{ \"email\": \"" + targetEmail + "\", \"currentPassword\": \"Password123\" }"))
                .andExpect(status().isOk());
        String emailChangeToken = emailConfirmationSender.tokenFor(targetEmail);
        assertThat(emailChangeToken).isNotEqualTo(registrationToken);

        try (Connection lockConnection = dataSource.getConnection();
                var executor = Executors.newSingleThreadExecutor()) {
            lockConnection.setAutoCommit(true);
            try (PreparedStatement lock = lockConnection.prepareStatement(
                    "SELECT pg_advisory_lock(hashtextextended(LOWER(?), 0))")) {
                lock.setString(1, targetEmail);
                lock.execute();
            }

            Future<Integer> confirmation = executor.submit(() -> mockMvc.perform(
                            confirmEmailRequest(emailChangeToken))
                    .andReturn()
                    .getResponse()
                    .getStatus());
            assertThat(awaitAdvisoryLockWaitOrCompletion(confirmation, targetEmail))
                    .as("email-change confirmation must serialize on the target email lock")
                    .isTrue();

            try (PreparedStatement unlock = lockConnection.prepareStatement(
                    "SELECT pg_advisory_unlock(hashtextextended(LOWER(?), 0))")) {
                unlock.setString(1, targetEmail);
                unlock.execute();
            }

            assertThat(confirmation.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        }

        User owner = userRepository.findByUsernameIgnoreCase("emailclaimowner").orElseThrow();
        assertThat(owner.getEmail()).isEqualTo(targetEmail);
        assertThat(owner.getPendingEmail()).isNull();
        assertThat(pendingRegistrationRepository.countByEmailIgnoreCaseAndExpiresAtAfter(
                targetEmail, LocalDateTime.now())).isZero();
        mockMvc.perform(confirmEmailRequest(registrationToken).cookie(registrationProof))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return BadRequest - When Changing Email Without CurrentPassword")
    void shouldReturnBadRequestWhenChangingEmailWithoutCurrentPassword() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("noemailconfirmuser", "noemailconfirmuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(patchMeRequest(accessTokenCookie, csrfCookie, "{ \"email\": \"newemail2@email.com\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("currentPassword must be provided to change password or email"));
    }

    @Test
    @DisplayName("[updateCurrentUser] Should Return Unauthorized - When Changing Email With Wrong CurrentPassword")
    void shouldReturnUnauthorizedWhenChangingEmailWithWrongCurrentPassword() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("wrongemailconfirmuser", "wrongemailconfirmuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("patchlockoutuser", "patchlockoutuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("interleaveuser", "interleaveuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("resendemailuser", "resendemailuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("nocsrfuser", "nocsrfuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("deleteuser", "deleteuser@email.com");

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
    @DisplayName("[deleteCurrentUser] Should Close Parent List Position Gaps - When Account Owns Nested Child Lists")
    void shouldCloseParentListPositionGapsWhenAccountOwnsNestedChildLists() throws Exception {
        MvcResult parentOwnerRegistration = registerConfirmAndLogin("nestedparentowner", "nestedparentowner@email.com");
        User parentOwner = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("nestedparentowner", "nestedparentowner")
                .orElseThrow();

        MvcResult deletingOwnerRegistration = registerConfirmAndLogin("nestedchildowner", "nestedchildowner@email.com");
        User deletingOwner = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("nestedchildowner", "nestedchildowner")
                .orElseThrow();

        LocalDateTime now = LocalDateTime.now();
        UserList parent = userListRepository.save(UserList.builder()
                .user(parentOwner)
                .name("Parent")
                .visibility(UserListVisibility.PUBLIC)
                .rank(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        UserList firstChild = saveChildList(parentOwner, "First child", now);
        UserList middleChild = saveChildList(deletingOwner, "Middle child", now);
        UserList lastChild = saveChildList(parentOwner, "Last child", now);
        persistNestedListItem(parent, firstChild, 1, now);
        persistNestedListItem(parent, middleChild, 2, now);
        persistNestedListItem(parent, lastChild, 3, now);

        Cookie accessTokenCookie = deletingOwnerRegistration.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = deletingOwnerRegistration.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        assertThat(userListRepository.findById(firstChild.getId())).isPresent();
        assertThat(userListRepository.findById(middleChild.getId())).isEmpty();
        assertThat(userListRepository.findById(lastChild.getId())).isPresent();
        assertThat(userListItemRepository.findByUserListIdOrderByPositionAsc(parent.getId()))
                .extracting(item -> item.getChildList().getId(), UserListItem::getPosition)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(firstChild.getId(), 1),
                        org.assertj.core.groups.Tuple.tuple(lastChild.getId(), 2));
    }

    private UserList saveChildList(User owner, String name, LocalDateTime now) {
        return userListRepository.save(UserList.builder()
                .user(owner)
                .name(name)
                .visibility(UserListVisibility.PUBLIC)
                .rank(null)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void persistNestedListItem(UserList parent, UserList child, int position, LocalDateTime now) {
        userListItemRepository.save(UserListItem.builder()
                .userList(parent)
                .childList(child)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Delete Account And Cascade The Watch Companion Row - When User Was Tagged As A Companion In Another User's Diary Entry")
    void shouldDeleteAccountAndCascadeWatchCompanionRowWhenUserWasTaggedAsACompanionInAnotherUsersDiaryEntry() throws Exception {
        registerConfirmAndLogin("diaryowner", "diaryowner@email.com");
        User owner = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("diaryowner", "diaryowner")
                .orElseThrow();

        MvcResult companionRegisterResult = registerConfirmAndLogin("companionuser", "companionuser@email.com");
        User companion = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("companionuser", "companionuser")
                .orElseThrow();

        Content movie = contentRepository.save(Content.builder()
                .tmdbId("550").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        DiaryEntry diaryEntry = diaryEntryRepository.save(DiaryEntry.builder()
                .user(owner).content(movie).watchNumber(1).watchedDate(LocalDate.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        watchCompanionRepository.save(WatchCompanion.builder()
                .diaryEntry(diaryEntry).user(companion).createdAt(LocalDateTime.now()).build());

        Cookie companionAccessTokenCookie = companionRegisterResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie companionCsrfCookie = companionRegisterResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(companionAccessTokenCookie, companionCsrfCookie, "{ \"password\": \"Password123\" }"))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("companionuser", "companionuser")).isEmpty();
        assertThat(watchCompanionRepository.findAll()).isEmpty();
        assertThat(diaryEntryRepository.findById(diaryEntry.getId())).isPresent();
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized And Keep Account - When Password Does Not Match")
    void shouldReturnUnauthorizedAndKeepAccountWhenPasswordDoesNotMatch() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("keepuser", "keepuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("deletelockoutuser", "deletelockoutuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("blankdeleteuser", "blankdeleteuser@email.com");

        Cookie accessTokenCookie = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = registerResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);

        mockMvc.perform(deleteMeRequest(accessTokenCookie, csrfCookie, "{ \"password\": \"\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[deleteCurrentUser] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForDeleteCurrentUser() throws Exception {
        MvcResult anonymousResult = mockMvc.perform(get("/protected-test-route"))
                .andExpect(cookie().exists(CookieUtil.CSRF_TOKEN_COOKIE))
                .andReturn();
        Cookie csrfCookie = anonymousResult.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
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
        MvcResult registerResult = registerConfirmAndLogin("doubledeleteuser", "doubledeleteuser@email.com");

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
        MvcResult registerResult = registerConfirmAndLogin("deletedtokenuser", "deletedtokenuser@email.com");

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

        registerConfirmAndLogin("targetpublic", "targetpublic@email.com");
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetpublic", "targetpublic")
                .orElseThrow();

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetUser.getId().toString()))
                .andExpect(jsonPath("$.username").value("targetpublic"))
                .andExpect(jsonPath("$.isProfilePublic").value(true))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").value(0))
                .andExpect(jsonPath("$.totalMinutesWatchedEpisodes").value(0))
                .andExpect(jsonPath("$.genreCountsMovies").isEmpty())
                .andExpect(jsonPath("$.genreCountsSeries").isEmpty());
    }

    @Test
    void shouldReturnOnlyNameAndUsernameForBlockedProfile() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("blockprofileviewer", "blockprofileviewer@email.com");
        registerConfirmAndLogin("blockprofiletarget", "blockprofiletarget@email.com");
        User viewer = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(
                "blockprofileviewer", "blockprofileviewer").orElseThrow();
        User target = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(
                "blockprofiletarget", "blockprofiletarget").orElseThrow();
        target.setDescription("private description");
        target.setProfilePicture("https://example.com/avatar.png");
        target.setBanner("https://example.com/banner.png");
        userRepository.save(target);
        userBlockRepository.saveAndFlush(UserBlock.builder()
                .blocker(viewer).blocked(target).createdAt(LocalDateTime.now()).build());

        mockMvc.perform(get("/users/" + target.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.username").value(target.getUsername()))
                .andExpect(jsonPath("$.name").value(target.getName()))
                .andExpect(jsonPath("$.blockRelationship").value("BLOCKED_BY_ME"))
                .andExpect(jsonPath("$.isContentRestrictedByBlock").value(true))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.profilePicture").doesNotExist())
                .andExpect(jsonPath("$.banner").doesNotExist())
                .andExpect(jsonPath("$.followersCount").doesNotExist())
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").doesNotExist())
                .andExpect(jsonPath("$.genreCountsMovies").doesNotExist());
    }

    @Test
    @DisplayName("[getUserById] Should Include Watch Time Stats - When Target User's Diary Has An Episode Entry")
    void shouldIncludeWatchTimeStatsWhenTargetUsersDiaryHasAnEpisodeEntry() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerstats", "viewerstats@email.com");

        registerConfirmAndLogin("targetstats", "targetstats@email.com");
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
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").value(0))
                .andExpect(jsonPath("$.totalMinutesWatchedEpisodes").value(55))
                .andExpect(jsonPath("$.genreCountsMovies").isEmpty())
                .andExpect(jsonPath("$.genreCountsSeries[*].genre", org.hamcrest.Matchers.containsInAnyOrder("Drama", "Action")))
                .andExpect(jsonPath("$.genreCountsSeries[*].count", org.hamcrest.Matchers.containsInAnyOrder(1, 1)));
    }

    @Test
    @DisplayName("[getUserById] Should Return Identity And Bio - When Target Profile Is Private")
    void shouldReturnIdentityAndBioWhenTargetProfileIsPrivate() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("viewerprivate", "viewerprivate@email.com");

        registerConfirmAndLogin("targetprivate", "targetprivate@email.com", false);
        User targetUser = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("targetprivate", "targetprivate")
                .orElseThrow();
        targetUser.setDescription("Bio remains visible while the profile is private");
        userRepository.saveAndFlush(targetUser);

        mockMvc.perform(get("/users/" + targetUser.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("targetprivate"))
                .andExpect(jsonPath("$.description").value("Bio remains visible while the profile is private"))
                .andExpect(jsonPath("$.isProfilePublic").value(false))
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").doesNotExist())
                .andExpect(jsonPath("$.followersCount").doesNotExist());
    }

    @Test
    @DisplayName("[getUserById] Should Return Private Profile - When Requester Is The Profile Owner")
    void shouldReturnPrivateProfileWhenRequesterIsTheProfileOwner() throws Exception {
        MvcResult registerResult = registerConfirmAndLogin("ownerprivate", "ownerprivate@email.com", false);
        Cookie ownerAccessToken = registerResult.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        User owner = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("ownerprivate", "ownerprivate")
                .orElseThrow();

        mockMvc.perform(get("/users/" + owner.getId()).cookie(ownerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ownerprivate"))
                .andExpect(jsonPath("$.isProfilePublic").value(false))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("[getUserById] Should Return Private Profile - When Requester Is An Accepted Follower")
    void shouldReturnPrivateProfileWhenRequesterIsAnAcceptedFollower() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("acceptedviewer", "acceptedviewer@email.com");
        registerConfirmAndLogin("acceptedtarget", "acceptedtarget@email.com", false);
        User viewer = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("acceptedviewer", "acceptedviewer")
                .orElseThrow();
        User target = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("acceptedtarget", "acceptedtarget")
                .orElseThrow();
        persistFollow(viewer, target, FollowStatus.ACCEPTED);

        mockMvc.perform(get("/users/" + target.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("acceptedtarget"))
                .andExpect(jsonPath("$.isProfilePublic").value(false))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("[getUserById] Should Return Identity And Bio - When Requester Has A Pending Follow Request")
    void shouldReturnIdentityAndBioWhenRequesterHasAPendingFollowRequest() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("pendingviewer", "pendingviewer@email.com");
        registerConfirmAndLogin("pendingtarget", "pendingtarget@email.com", false);
        User viewer = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("pendingviewer", "pendingviewer")
                .orElseThrow();
        User target = userRepository
                .findByUsernameIgnoreCaseOrEmailIgnoreCase("pendingtarget", "pendingtarget")
                .orElseThrow();
        persistFollow(viewer, target, FollowStatus.PENDING);

        mockMvc.perform(get("/users/" + target.getId()).cookie(viewerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("pendingtarget"))
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").doesNotExist());
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
        registerConfirmAndLogin("targetnoauth", "targetnoauth@email.com");
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
        registerConfirmAndLogin("searchuser1", "searchuser1@email.com");
        registerConfirmAndLogin("searchuser2", "searchuser2@email.com");
        registerConfirmAndLogin("unrelated", "unrelated@email.com");

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
        registerConfirmAndLogin("privatesearchtarget", "privatesearchtarget@email.com", false);

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
        registerConfirmAndLogin("sizeuser1", "sizeuser1@email.com");
        registerConfirmAndLogin("sizeuser2", "sizeuser2@email.com");
        registerConfirmAndLogin("sizeuser3", "sizeuser3@email.com");

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
        registerConfirmAndLogin("pageuser1", "pageuser1@email.com");
        registerConfirmAndLogin("pageuser2", "pageuser2@email.com");
        registerConfirmAndLogin("pageuser3", "pageuser3@email.com");

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
        registerConfirmAndLogin("noauthsearchtarget", "noauthsearchtarget@email.com");

        mockMvc.perform(get("/users").param("username", "noauthsearchtarget"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getUsersByUsername][getUserById] Should Return TooManyRequests - When Combined Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenCombinedProfileRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        Cookie viewerAccessToken = registerAndGetAccessToken("scanviewer", "scanviewer@email.com");
        registerConfirmAndLogin("scantarget", "scantarget@email.com");
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

    @Test
    void shouldReturnNewestTenSettingsPreviewsWithFullCountsAndHidePendingWhenPublic() throws Exception {
        Cookie token = registerAndGetAccessToken("settingsowner", "settingsowner@email.com");
        User owner = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase("settingsowner", "settingsowner").orElseThrow();
        owner.setIsProfilePublic(false);
        owner.setName("Settings Owner");
        owner.setDescription("Settings bio");
        owner.setProfilePicture("https://example.com/avatar");
        owner.setBanner("https://example.com/banner");
        owner.setPreferredLanguage("pt-BR");
        owner.setPreferredRegion("BR");
        userRepository.saveAndFlush(owner);
        LocalDateTime base = LocalDateTime.of(2026, 10, 16, 12, 0);
        for (int i = 0; i < 11; i++) {
            User requester = userRepository.saveAndFlush(buildUser("requester" + i, "requester" + i + "@email.com"));
            User blocked = userRepository.saveAndFlush(buildUser("blocked" + i, "blocked" + i + "@email.com"));
            followerRepository.saveAndFlush(Follower.builder().follower(requester).followed(owner)
                    .status(FollowStatus.PENDING).createdAt(base.plusMinutes(i)).build());
            userBlockRepository.saveAndFlush(UserBlock.builder().blocker(owner).blocked(blocked)
                    .createdAt(base.plusMinutes(i)).build());
        }
        User accepted = userRepository.saveAndFlush(buildUser("acceptedsettings", "acceptedsettings@email.com"));
        persistFollow(accepted, owner, FollowStatus.ACCEPTED);
        persistFollow(owner, accepted, FollowStatus.ACCEPTED);
        // An incoming block must not enter the owner's outgoing block section.
        userBlockRepository.saveAndFlush(UserBlock.builder().blocker(accepted).blocked(owner).createdAt(base).build());

        mockMvc.perform(get("/users/me/settings").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.profile.username").value("settingsowner"))
                .andExpect(jsonPath("$.profile.name").value("Settings Owner"))
                .andExpect(jsonPath("$.profile.description").value("Settings bio"))
                .andExpect(jsonPath("$.profile.profilePicture").value("https://example.com/avatar"))
                .andExpect(jsonPath("$.profile.banner").value("https://example.com/banner"))
                .andExpect(jsonPath("$.profile.isProfilePublic").value(false))
                .andExpect(jsonPath("$.privacy.isProfilePublic").doesNotExist())
                .andExpect(jsonPath("$.privacy.followersCount").value(1))
                .andExpect(jsonPath("$.privacy.followingCount").value(1))
                .andExpect(jsonPath("$.privacy.pendingFollowRequestsCount").value(11))
                .andExpect(jsonPath("$.privacy.pendingFollowRequests.length()").value(10))
                .andExpect(jsonPath("$.privacy.pendingFollowRequests[*].username", org.hamcrest.Matchers.contains(
                        "requester10", "requester9", "requester8", "requester7", "requester6", "requester5", "requester4", "requester3", "requester2", "requester1")))
                .andExpect(jsonPath("$.privacy.blockedUsersCount").value(11))
                .andExpect(jsonPath("$.privacy.blockedUsers.length()").value(10))
                .andExpect(jsonPath("$.privacy.blockedUsers[*].username", org.hamcrest.Matchers.contains(
                        "blocked10", "blocked9", "blocked8", "blocked7", "blocked6", "blocked5", "blocked4", "blocked3", "blocked2", "blocked1")))
                .andExpect(jsonPath("$.contentPreferences.preferredLanguage").value("pt-BR"))
                .andExpect(jsonPath("$.contentPreferences.preferredRegion").value("BR"))
                .andExpect(jsonPath("$.account.email").value("settingsowner@email.com"))
                .andExpect(jsonPath("$..password").isEmpty())
                .andExpect(jsonPath("$..role").isEmpty())
                .andExpect(jsonPath("$..sessionsInvalidatedAt").isEmpty())
                .andExpect(jsonPath("$.privacy..email").isEmpty())
                .andExpect(jsonPath("$..genreCountsMovies").isEmpty())
                .andExpect(jsonPath("$..totalMinutesWatchedMovies").isEmpty());

        owner.setIsProfilePublic(true);
        userRepository.saveAndFlush(owner);
        mockMvc.perform(get("/users/me/settings").cookie(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.isProfilePublic").value(true))
                .andExpect(jsonPath("$.privacy.pendingFollowRequestsCount").value(0))
                .andExpect(jsonPath("$.privacy.pendingFollowRequests").isEmpty())
                .andExpect(jsonPath("$.privacy.blockedUsersCount").value(11));
    }

    @Test
    void shouldRequireAuthenticationForSettings() throws Exception {
        mockMvc.perform(get("/users/me/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnOnlyAuthenticatedUsersSettings() throws Exception {
        Cookie first = registerAndGetAccessToken("settingsfirst", "settingsfirst@email.com");
        Cookie second = registerAndGetAccessToken("settingssecond", "settingssecond@email.com");
        mockMvc.perform(get("/users/me/settings").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value("settingsfirst@email.com"))
                .andExpect(jsonPath("$.privacy.blockedUsersCount").value(0));
        mockMvc.perform(get("/users/me/settings").cookie(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.email").value("settingssecond@email.com"));
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
                .name(username)
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
        MvcResult result = registerConfirmAndLogin(username, email);
        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        return accessTokenCookie;
    }

    private MvcResult registerConfirmAndLogin(String username, String email) throws Exception {
        return registerConfirmAndLogin(username, email, true);
    }

    private MvcResult registerConfirmAndLogin(String username, String email, boolean isProfilePublic) throws Exception {
        MvcResult registration = mockMvc.perform(registerRequest(username, email, isProfilePublic))
                .andExpect(status().isAccepted())
                .andReturn();
        Cookie registrationProof = registration.getResponse().getCookie(CookieUtil.REGISTRATION_PROOF_COOKIE);
        mockMvc.perform(confirmEmailRequest(emailConfirmationSender.tokenFor(email)).cookie(registrationProof))
                .andExpect(status().isNoContent());
        return mockMvc.perform(loginRequest(username)).andExpect(status().isOk()).andReturn();
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
        throw new AssertionError("Operation neither waited for the requested email lock nor completed");
    }

    private MockHttpServletRequestBuilder loginRequest(String identifier) {
        return post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"Password123\"}");
    }

    private MockHttpServletRequestBuilder confirmEmailRequest(String token) {
        return post("/auth/confirm-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}");
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
                    "name": "%s",
                    "email": "%s",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, username, email, isProfilePublic);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
