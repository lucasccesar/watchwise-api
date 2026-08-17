package com.watchwise.watchwise_api.diaryentry.controller;

import com.jayway.jsonpath.JsonPath;
import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.Follower;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DiaryEntryControllerIntegrationTest {

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
    private ContentRepository contentRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private FollowerRepository followerRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        return registerUser(username, true);
    }

    private RegisteredUser registerUser(String username, boolean isProfilePublic) throws Exception {
        MvcResult result = mockMvc.perform(registerRequest(username, isProfilePublic))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessTokenCookie, csrfCookie);
    }

    private MockHttpServletRequestBuilder registerRequest(String username, boolean isProfilePublic) {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, username, isProfilePublic);

        return post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void persistFollow(UUID followerId, UUID followedId, FollowStatus status) {
        followerRepository.save(Follower.builder()
                .follower(userRepository.getReferenceById(followerId))
                .followed(userRepository.getReferenceById(followedId))
                .status(status)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private MockHttpServletRequestBuilder getDiaryRequest(RegisteredUser viewer, UUID targetUserId) {
        return get("/users/" + targetUserId + "/diary").cookie(viewer.accessToken());
    }

    private MockHttpServletRequestBuilder createRequest(RegisteredUser actor, String body) {
        return post("/diary")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder updateRequest(RegisteredUser actor, UUID diaryEntryId, String body) {
        return patch("/diary/" + diaryEntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser actor, UUID diaryEntryId) {
        return delete("/diary/" + diaryEntryId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder bulkRequest(RegisteredUser actor, String body) {
        return post("/diary/bulk")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String creationBody(String tmdbId, Integer score) {
        String scoreField = score == null ? "" : (", \"score\": " + score);
        return """
                {
                    "content": { "tmdbId": "%s", "type": "MOVIE" }%s
                }
                """.formatted(tmdbId, scoreField);
    }

    private String creationBody(String tmdbId, String type, Boolean watchedInTheater) {
        return """
                {
                    "content": { "tmdbId": "%s", "type": "%s" },
                    "watchedInTheater": %s
                }
                """.formatted(tmdbId, type, watchedInTheater);
    }

    private String updateBody(Integer score) {
        return """
                {
                    "score": %s
                }
                """.formatted(score);
    }

    private String updateBody(String field, Object value) {
        String formattedValue = value instanceof String stringValue ? "\"" + stringValue + "\"" : String.valueOf(value);
        return """
                {
                    "%s": %s
                }
                """.formatted(field, formattedValue);
    }

    private String episodeBody(String seriesTmdbId, int seasonNumber, int episodeNumber, Boolean isSeasonFinale, Boolean isSeriesFinale) {
        return """
                {
                    "content": {
                        "type": "EPISODE",
                        "seriesTmdbId": "%s",
                        "seasonNumber": %d,
                        "episodeNumber": %d,
                        "isSeasonFinale": %s,
                        "isSeriesFinale": %s
                    }
                }
                """.formatted(seriesTmdbId, seasonNumber, episodeNumber, isSeasonFinale, isSeriesFinale);
    }

    private DiaryEntry persistEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return diaryEntryRepository.save(DiaryEntry.builder()
                .user(user)
                .content(content)
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Content persistContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    // ---------- GET /users/{userId}/diary ----------

    @Test
    @DisplayName("[getDiaryEntries] Should Return The Entries Most Recently Created First - When Entries Exist")
    void shouldReturnTheEntriesMostRecentlyCreatedFirstWhenEntriesExist() throws Exception {
        RegisteredUser user = registerUser("getdiaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content fightClub = persistContent("550", ContentType.MOVIE);
        Content pulpFiction = persistContent("680", ContentType.MOVIE);
        DiaryEntry older = persistEntry(entity, fightClub);
        older.setCreatedAt(LocalDateTime.now().minusDays(1));
        diaryEntryRepository.save(older);
        persistEntry(entity, pulpFiction);

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].content.tmdbId").value("680"))
                .andExpect(jsonPath("$.content[1].content.tmdbId").value("550"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Empty Content - When User Has No Entries")
    void shouldReturnEmptyContentWhenUserHasNoEntries() throws Exception {
        RegisteredUser user = registerUser("getdiaryempty");

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Only Return Entries Watched In The Given Year - When Year Is Provided")
    void shouldOnlyReturnEntriesWatchedInTheGivenYearWhenYearIsProvided() throws Exception {
        RegisteredUser user = registerUser("getdiaryyear");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content fightClub = persistContent("550", ContentType.MOVIE);
        Content pulpFiction = persistContent("680", ContentType.MOVIE);
        DiaryEntry watchedIn2023 = persistEntry(entity, fightClub);
        watchedIn2023.setWatchedDate(LocalDate.of(2023, 5, 1));
        diaryEntryRepository.save(watchedIn2023);
        DiaryEntry watchedIn2024 = persistEntry(entity, pulpFiction);
        watchedIn2024.setWatchedDate(LocalDate.of(2024, 5, 1));
        diaryEntryRepository.save(watchedIn2024);

        mockMvc.perform(getDiaryRequest(user, user.id()).param("year", "2024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.tmdbId").value("680"));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("getdiarynotfound");

        mockMvc.perform(getDiaryRequest(viewer, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return BadRequest - When Year Is Out Of Range")
    void shouldReturnBadRequestWhenYearIsOutOfRange() throws Exception {
        RegisteredUser user = registerUser("getdiaryinvalidyear");

        mockMvc.perform(getDiaryRequest(user, user.id()).param("year", "2147483647"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForGet() throws Exception {
        RegisteredUser user = registerUser("getdiarynoauth");

        mockMvc.perform(get("/users/" + user.id() + "/diary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("getdiaryforbiddenviewer");
        RegisteredUser target = registerUser("getdiaryforbiddentarget", false);

        mockMvc.perform(getDiaryRequest(viewer, target.id()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("getdiaryacceptedviewer");
        RegisteredUser target = registerUser("getdiaryacceptedtarget", false);
        persistFollow(viewer.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(getDiaryRequest(viewer, target.id()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Entries - When Target Profile Is Private And Viewer Is The Target Themselves")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsTheTargetThemselves() throws Exception {
        RegisteredUser target = registerUser("getdiaryselftarget", false);

        mockMvc.perform(getDiaryRequest(target, target.id()))
                .andExpect(status().isOk());
    }

    // ---------- POST /diary ----------

    @Test
    @DisplayName("[createDiaryEntry] Should Return Created And Persist The Entry - When Payload Is Valid")
    void shouldReturnCreatedAndPersistTheEntryWhenPayloadIsValid() throws Exception {
        RegisteredUser user = registerUser("creatediaryok");

        mockMvc.perform(createRequest(user, creationBody("550", 8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.score").value(8))
                .andExpect(jsonPath("$.entry.content.tmdbId").value("550"))
                .andExpect(jsonPath("$.entry.watchNumber").value(1));

        User entity = userRepository.findById(user.id()).orElseThrow();
        assertThat(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(entity.getId(), PageRequest.of(0, 10)))
                .hasSize(1);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return BadRequest And Not Persist - When Content Is Missing")
    void shouldReturnBadRequestAndNotPersistWhenContentIsMissing() throws Exception {
        RegisteredUser user = registerUser("creatediarymissingcontent");

        mockMvc.perform(createRequest(user, "{ \"score\": 5 }"))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return BadRequest And Not Persist - When Score Is Above Maximum")
    void shouldReturnBadRequestAndNotPersistWhenScoreIsAboveMaximum() throws Exception {
        RegisteredUser user = registerUser("creatediaryscoretoohigh");

        mockMvc.perform(createRequest(user, creationBody("550", 11)))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return BadRequest And Not Persist - When WatchedInTheater Is Set And Content Type Is Not Movie")
    void shouldReturnBadRequestAndNotPersistWhenWatchedInTheaterIsSetAndContentTypeIsNotMovie() throws Exception {
        RegisteredUser user = registerUser("creatediarywatchedintheaternotmovie");

        mockMvc.perform(createRequest(user, creationBody("2316", "SERIES", true)))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Set WatchNumber To 2 And Persist - When User Already Logged This Content")
    void shouldSetWatchNumberToTwoAndPersistWhenUserAlreadyLoggedThisContent() throws Exception {
        RegisteredUser user = registerUser("creatediaryrewatch");
        User entity = userRepository.findById(user.id()).orElseThrow();
        persistEntry(entity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(createRequest(user, creationBody("550", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.watchNumber").value(2));

        assertThat(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(entity.getId(), PageRequest.of(0, 10)))
                .hasSize(2);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Succeed For Both Requests - When Two Users Concurrently Log The Same Not-Yet-Existing Content")
    void shouldSucceedForBothRequestsWhenTwoUsersConcurrentlyLogTheSameNotYetExistingContent() throws Exception {
        RegisteredUser userA = registerUser("racecontentusera");
        RegisteredUser userB = registerUser("racecontentuserb");
        String body = creationBody("999999", null);

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> callA = () -> {
            barrier.await();
            return mockMvc.perform(createRequest(userA, body)).andReturn().getResponse().getStatus();
        };
        Callable<Integer> callB = () -> {
            barrier.await();
            return mockMvc.perform(createRequest(userB, body)).andReturn().getResponse().getStatus();
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> futureA = executor.submit(callA);
            Future<Integer> futureB = executor.submit(callB);

            assertThat(futureA.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CREATED.value());
            assertThat(futureB.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CREATED.value());
        } finally {
            executor.shutdownNow();
        }

        assertThat(contentRepository.findByTmdbIdAndType("999999", ContentType.MOVIE)).isPresent();
        assertThat(diaryEntryRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return TooManyRequests - When Requests From The Same User Exceed The Configured Max")
    void shouldReturnTooManyRequestsWhenRequestsFromTheSameUserExceedTheConfiguredMax() throws Exception {
        RegisteredUser user = registerUser("creatediaryratelimit");

        for (int i = 0; i < 60; i++) {
            mockMvc.perform(createRequest(user, creationBody("movie" + i, null)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(createRequest(user, creationBody("onemore", null)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForCreate() throws Exception {
        RegisteredUser user = registerUser("creatediarynoauth");

        mockMvc.perform(post("/diary")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creationBody("550", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForCreate() throws Exception {
        RegisteredUser user = registerUser("creatediarynocsrf");

        mockMvc.perform(post("/diary")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creationBody("550", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return BadRequest And Not Persist - When EpisodeNumber Is Zero")
    void shouldReturnBadRequestAndNotPersistWhenEpisodeNumberIsZero() throws Exception {
        RegisteredUser user = registerUser("creatediaryepisodezero");

        mockMvc.perform(createRequest(user, episodeBody("999", 1, 0, true, null)))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
        assertThat(contentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return BadRequest And Not Persist - When SeasonNumber Is Negative")
    void shouldReturnBadRequestAndNotPersistWhenSeasonNumberIsNegative() throws Exception {
        RegisteredUser user = registerUser("creatediaryseasonnegative");

        mockMvc.perform(createRequest(user, episodeBody("999", -1, 1, true, null)))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
        assertThat(contentRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Include CompletedSeason In The Response - When Logging The Finale Episode Completes The Season")
    void shouldIncludeCompletedSeasonInTheResponseWhenLoggingTheFinaleEpisodeCompletesTheSeason() throws Exception {
        RegisteredUser user = registerUser("completionsignaluser");

        mockMvc.perform(createRequest(user, episodeBody("902", 1, 1, true, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.id").exists())
                .andExpect(jsonPath("$.completedSeason.watchNumber").value(1))
                .andExpect(jsonPath("$.completedSeries").doesNotExist());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Omit CompletedSeason And CompletedSeries - When Logging The Episode Does Not Complete Anything")
    void shouldOmitCompletedSeasonAndCompletedSeriesWhenLoggingTheEpisodeDoesNotCompleteAnything() throws Exception {
        RegisteredUser user = registerUser("nocompletionsignaluser");

        mockMvc.perform(createRequest(user, episodeBody("903", 1, 2, false, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedSeason").doesNotExist())
                .andExpect(jsonPath("$.completedSeries").doesNotExist());
    }

    // ---------- PATCH /diary/{diaryEntryId} ----------

    @Test
    @DisplayName("[updateDiaryEntry] Should Return Ok And Persist The Change - When Owner Updates The Score")
    void shouldReturnOkAndPersistTheChangeWhenOwnerUpdatesTheScore() throws Exception {
        RegisteredUser user = registerUser("updatediaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        DiaryEntry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(updateRequest(user, entry.getId(), updateBody(9)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(9));

        assertThat(diaryEntryRepository.findById(entry.getId()).orElseThrow().getScore()).isEqualTo(9);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExist() throws Exception {
        RegisteredUser user = registerUser("updatediarynotfound");

        mockMvc.perform(updateRequest(user, UUID.randomUUID(), updateBody(9)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUser() throws Exception {
        RegisteredUser owner = registerUser("updatediaryowner");
        RegisteredUser intruder = registerUser("updatediaryintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        DiaryEntry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(updateRequest(intruder, entry.getId(), updateBody(9)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));

        assertThat(diaryEntryRepository.findById(entry.getId()).orElseThrow().getScore()).isNull();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return BadRequest And Not Persist - When Score Is Above Maximum")
    void shouldReturnBadRequestAndNotPersistWhenScoreIsAboveMaximumOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatediaryscoretoohigh");
        User entity = userRepository.findById(user.id()).orElseThrow();
        DiaryEntry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(updateRequest(user, entry.getId(), updateBody(11)))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findById(entry.getId()).orElseThrow().getScore()).isNull();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return BadRequest And Not Persist - When WatchedInTheater Is Set And Content Type Is Not Movie")
    void shouldReturnBadRequestAndNotPersistWhenWatchedInTheaterIsSetAndContentTypeIsNotMovieOnUpdate() throws Exception {
        RegisteredUser user = registerUser("updatediarywatchedintheaternotmovie");
        User entity = userRepository.findById(user.id()).orElseThrow();
        DiaryEntry entry = persistEntry(entity, persistContent("2316", ContentType.SERIES));

        mockMvc.perform(updateRequest(user, entry.getId(), updateBody("watchedInTheater", true)))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findById(entry.getId()).orElseThrow().getWatchedInTheater()).isNull();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatediarynoauth");

        mockMvc.perform(patch("/diary/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(9)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForUpdate() throws Exception {
        RegisteredUser user = registerUser("updatediarynocsrf");

        mockMvc.perform(patch("/diary/" + UUID.randomUUID())
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(9)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Ignore IsRewatch And Return WatchNumber - When IsRewatch Is Sent In The Request Body")
    void shouldIgnoreIsRewatchAndReturnWatchNumberWhenSentInTheUpdateRequestBody() throws Exception {
        RegisteredUser user = registerUser("patchisrewatchrejected");
        MvcResult created = mockMvc.perform(createRequest(user, creationBody("550", null))).andReturn();
        UUID entryId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.entry.id"));

        mockMvc.perform(updateRequest(user, entryId, "{ \"isRewatch\": true }"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isRewatch").doesNotExist())
                .andExpect(jsonPath("$.watchNumber").value(1));
    }

    // ---------- DELETE /diary/{diaryEntryId} ----------

    @Test
    @DisplayName("[deleteDiaryEntry] Should Return NoContent And Remove The Row - When Owner Deletes It")
    void shouldReturnNoContentAndRemoveTheRowWhenOwnerDeletesIt() throws Exception {
        RegisteredUser user = registerUser("deletediaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        DiaryEntry entry = persistEntry(entity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(deleteRequest(user, entry.getId()))
                .andExpect(status().isNoContent());

        assertThat(diaryEntryRepository.findById(entry.getId())).isEmpty();
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExistOnDelete() throws Exception {
        RegisteredUser user = registerUser("deletediarynotfound");

        mockMvc.perform(deleteRequest(user, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUserOnDelete() throws Exception {
        RegisteredUser owner = registerUser("deletediaryowner");
        RegisteredUser intruder = registerUser("deletediaryintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        DiaryEntry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(deleteRequest(intruder, entry.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));

        assertThat(diaryEntryRepository.findById(entry.getId())).isPresent();
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForDelete() throws Exception {
        RegisteredUser user = registerUser("deletediarynoauth");

        mockMvc.perform(delete("/diary/" + UUID.randomUUID())
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForDelete() throws Exception {
        RegisteredUser user = registerUser("deletediarynocsrf");

        mockMvc.perform(delete("/diary/" + UUID.randomUUID())
                        .cookie(user.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Wipe Auto-Generated Series History - When The Series Entry Is Deleted Directly")
    void shouldWipeAutoGeneratedSeriesHistoryWhenTheSeriesEntryIsDeletedDirectly() throws Exception {
        RegisteredUser user = registerUser("wipeseries");

        mockMvc.perform(createRequest(user, episodeBody("1500", 1, 1, true, true)))
                .andExpect(status().isCreated());

        MvcResult diaryResult = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andReturn();

        List<String> seriesEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SERIES')].id");
        String seriesEntryId = seriesEntryIds.get(0);

        mockMvc.perform(deleteRequest(user, UUID.fromString(seriesEntryId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.type").value("EPISODE"))
                .andExpect(jsonPath("$.content[0].autoGenerated").value(false));
    }

    // ---------- Season/series auto-completion ----------

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create Season Entry - When The Last Missing Episode Of A 2-Episode Season Is Logged")
    void shouldAutoCreateSeasonEntryWhenTheLastMissingEpisodeOfATwoEpisodeSeasonIsLogged() throws Exception {
        RegisteredUser user = registerUser("autoseasonok");

        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, false, false)))
                .andExpect(status().isCreated());
        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 2, true, false)))
                .andExpect(status().isCreated());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].content.type").value("SEASON"))
                .andExpect(jsonPath("$.content[0].content.seasonNumber").value(1))
                .andExpect(jsonPath("$.content[0].autoGenerated").value(true));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Season Entry - When An Episode Is Still Missing")
    void shouldNotAutoCreateSeasonEntryWhenAnEpisodeIsStillMissing() throws Exception {
        RegisteredUser user = registerUser("autoseasonincomplete");

        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 2, true, false)))
                .andExpect(status().isCreated());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.type").value("EPISODE"));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create Series Entry - When The Last Missing Season Is Completed")
    void shouldAutoCreateSeriesEntryWhenTheLastMissingSeasonIsCompleted() throws Exception {
        RegisteredUser user = registerUser("autoseriesok");

        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, true, true)))
                .andExpect(status().isCreated());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].content.type").value("SERIES"))
                .andExpect(jsonPath("$.content[0].autoGenerated").value(true));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Retract Auto-Generated Season Entry - When The Completing Episode Is Deleted")
    void shouldRetractAutoGeneratedSeasonEntryWhenTheCompletingEpisodeIsDeleted() throws Exception {
        RegisteredUser user = registerUser("retractseasonok");

        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, false, false)))
                .andExpect(status().isCreated());
        MvcResult secondEpisode = mockMvc.perform(createRequest(user, episodeBody("1399", 1, 2, true, false)))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));

        String secondEpisodeId = JsonPath.read(secondEpisode.getResponse().getContentAsString(), "$.entry.id");

        mockMvc.perform(deleteRequest(user, UUID.fromString(secondEpisodeId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.type").value("EPISODE"));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Retract Only The Second Pass - When The User Rewatched A Season Twice And Deletes An Episode From The Second Pass")
    void shouldRetractOnlyTheSecondPassWhenTheUserRewatchedASeasonTwiceAndDeletesAnEpisodeFromTheSecondPass() throws Exception {
        RegisteredUser user = registerUser("retractsecondpass");

        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, true, false)))
                .andExpect(status().isCreated());
        MvcResult secondWatch = mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, true, false)))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4));

        String secondWatchId = JsonPath.read(secondWatch.getResponse().getContentAsString(), "$.entry.id");

        mockMvc.perform(deleteRequest(user, UUID.fromString(secondWatchId)))
                .andExpect(status().isNoContent());

        MvcResult result = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andReturn();

        List<Integer> seasonWatchNumbers = JsonPath.read(
                result.getResponse().getContentAsString(), "$.content[?(@.content.type=='SEASON')].watchNumber");
        assertThat(seasonWatchNumbers).containsExactly(1);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Create A Second Auto-Generated Season Entry With WatchNumber 2 - When The User Fully Rewatches A Completed Season")
    void shouldCreateASecondAutoGeneratedSeasonEntryWithWatchNumberTwoWhenTheUserFullyRewatchesACompletedSeason() throws Exception {
        RegisteredUser user = registerUser("fullseasonrewatch");

        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, true, false)))
                .andExpect(status().isCreated());
        mockMvc.perform(createRequest(user, episodeBody("1399", 1, 1, true, false)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andReturn();

        List<Integer> seasonWatchNumbers = JsonPath.read(
                result.getResponse().getContentAsString(), "$.content[?(@.content.type=='SEASON')].watchNumber");
        assertThat(seasonWatchNumbers).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Remove A Manually-Edited Season Entry - When OverrideProtectedEntries Is True")
    void shouldRemoveAManuallyEditedSeasonEntryWhenOverrideProtectedEntriesIsTrue() throws Exception {
        RegisteredUser user = registerUser("overridedeleteseason");

        mockMvc.perform(createRequest(user, episodeBody("2500", 1, 1, true, false)))
                .andExpect(status().isCreated());

        MvcResult diaryResult = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        List<String> seasonEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SEASON')].id");
        String seasonEntryId = seasonEntryIds.get(0);

        List<String> episodeEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'EPISODE')].id");
        String episodeEntryId = episodeEntryIds.get(0);

        mockMvc.perform(updateRequest(user, UUID.fromString(seasonEntryId), updateBody("comment", "Great season!")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/diary/" + episodeEntryId + "?overrideProtectedEntries=true")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue()))
                .andExpect(status().isNoContent());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Preserve A Manually-Edited Season Entry - When OverrideProtectedEntries Is Absent")
    void shouldPreserveAManuallyEditedSeasonEntryWhenOverrideProtectedEntriesIsAbsent() throws Exception {
        RegisteredUser user = registerUser("preservedeleteseason");

        mockMvc.perform(createRequest(user, episodeBody("2600", 1, 1, true, false)))
                .andExpect(status().isCreated());

        MvcResult diaryResult = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        List<String> seasonEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SEASON')].id");
        String seasonEntryId = seasonEntryIds.get(0);

        List<String> episodeEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'EPISODE')].id");
        String episodeEntryId = episodeEntryIds.get(0);

        mockMvc.perform(updateRequest(user, UUID.fromString(seasonEntryId), updateBody("comment", "Great season!")))
                .andExpect(status().isOk());

        mockMvc.perform(deleteRequest(user, UUID.fromString(episodeEntryId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].content.type").value("SEASON"));
    }

    // ---------- POST /diary/bulk ----------

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Create Every Episode And The Season Entry - When Bulk-Logging A Complete Season")
    void shouldCreateEveryEpisodeAndTheSeasonEntryWhenBulkLoggingACompleteSeason() throws Exception {
        RegisteredUser user = registerUser("bulkseasonuser");
        String body = """
                {
                    "content": { "type": "SEASON", "seriesTmdbId": "901", "seasonNumber": 1 },
                    "watchedDate": "2025-03-12",
                    "finaleEpisodeNumber": 3
                }
                """;

        MvcResult bulkResult = mockMvc.perform(post("/diary/bulk")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        List<Boolean> seasonEntryAutoGenerated = JsonPath.read(
                bulkResult.getResponse().getContentAsString(), "$[?(@.content.type == 'SEASON')].autoGenerated");
        assertThat(seasonEntryAutoGenerated).containsExactly(true);

        List<Integer> seasonEntryWatchNumber = JsonPath.read(
                bulkResult.getResponse().getContentAsString(), "$[?(@.content.type == 'SEASON')].watchNumber");
        assertThat(seasonEntryWatchNumber).containsExactly(1);

        List<DiaryEntry> allEntries = diaryEntryRepository.findAll();
        assertThat(allEntries).hasSize(4);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return TooManyRequests - When The Bulk Rate Limit Is Exceeded")
    void shouldReturnTooManyRequestsWhenTheBulkRateLimitIsExceeded() throws Exception {
        RegisteredUser user = registerUser("bulkratelimit");
        String body = """
                {
                    "content": { "type": "SEASON", "seriesTmdbId": "901", "seasonNumber": 1 },
                    "watchedDate": "2025-03-12",
                    "finaleEpisodeNumber": 3
                }
                """;

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/diary/bulk")
                            .cookie(user.accessToken(), user.csrfToken())
                            .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body.replace("901", "90" + i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/diary/bulk")
                        .cookie(user.accessToken(), user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForBulk() throws Exception {
        RegisteredUser user = registerUser("bulknoauth");
        String body = """
                {
                    "content": { "type": "SEASON", "seriesTmdbId": "910", "seasonNumber": 1 },
                    "finaleEpisodeNumber": 2
                }
                """;

        mockMvc.perform(post("/diary/bulk")
                        .cookie(user.csrfToken())
                        .header("X-XSRF-TOKEN", user.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissingForBulk() throws Exception {
        RegisteredUser user = registerUser("bulknocsrf");
        String body = """
                {
                    "content": { "type": "SEASON", "seriesTmdbId": "911", "seasonNumber": 1 },
                    "finaleEpisodeNumber": 2
                }
                """;

        mockMvc.perform(post("/diary/bulk")
                        .cookie(user.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return BadRequest And Not Persist - When The Season Has No Known Finale And FinaleEpisodeNumber Is Absent")
    void shouldReturnBadRequestAndNotPersistWhenTheSeasonHasNoKnownFinaleAndFinaleEpisodeNumberIsAbsent() throws Exception {
        RegisteredUser user = registerUser("bulkmissingfinale");
        String body = """
                {
                    "content": { "type": "SEASON", "seriesTmdbId": "912", "seasonNumber": 1 }
                }
                """;

        mockMvc.perform(bulkRequest(user, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("finaleEpisodeNumber is required when season 1 has no known finale episode yet"));

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return BadRequest And Not Persist - When FinaleEpisodeNumber Is Zero")
    void shouldReturnBadRequestAndNotPersistWhenFinaleEpisodeNumberIsZero() throws Exception {
        RegisteredUser user = registerUser("bulkfinalezero");
        String body = """
                {
                    "content": { "type": "SEASON", "seriesTmdbId": "913", "seasonNumber": 1 },
                    "finaleEpisodeNumber": 0
                }
                """;

        mockMvc.perform(bulkRequest(user, body))
                .andExpect(status().isBadRequest());

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return BadRequest And Not Persist - When The Content Type Is MOVIE")
    void shouldReturnBadRequestAndNotPersistWhenTheContentTypeIsMovieOnBulk() throws Exception {
        RegisteredUser user = registerUser("bulkmovietype");
        String body = """
                {
                    "content": { "type": "MOVIE", "tmdbId": "550" }
                }
                """;

        mockMvc.perform(bulkRequest(user, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bulk logging only supports content of type SEASON or SERIES"));

        assertThat(diaryEntryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Return Empty WouldDelete - When The Only Impacted Entry Was Manually Edited And OverrideProtectedEntries Is Absent")
    void shouldReturnEmptyWouldDeleteWhenTheOnlyImpactedEntryWasManuallyEditedAndOverrideProtectedEntriesIsAbsent() throws Exception {
        RegisteredUser user = registerUser("deletionimpactdefault");

        mockMvc.perform(createRequest(user, episodeBody("2001", 1, 1, true, false)))
                .andExpect(status().isCreated());

        MvcResult diaryResult = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        List<String> seasonEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SEASON')].id");
        List<String> episodeEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'EPISODE')].id");

        mockMvc.perform(updateRequest(user, UUID.fromString(seasonEntryIds.get(0)), updateBody("comment", "Great season!")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/diary/" + episodeEntryIds.get(0) + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldDelete.length()").value(0));
    }

    @Test
    @DisplayName("[getDeletionImpact] Should List The Season Entry With HasReview True And AutoGenerated False - When It Was Manually Edited And OverrideProtectedEntries Is True")
    void shouldListTheSeasonEntryWithHasReviewTrueAndAutoGeneratedFalseWhenItWasManuallyEditedAndOverrideProtectedEntriesIsTrue() throws Exception {
        RegisteredUser user = registerUser("deletionimpactuser");

        mockMvc.perform(createRequest(user, episodeBody("2001", 1, 1, true, false)))
                .andExpect(status().isCreated());

        MvcResult diaryResult = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        List<String> seasonEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SEASON')].id");
        String seasonEntryId = seasonEntryIds.get(0);

        List<String> episodeEntryIds = JsonPath.read(diaryResult.getResponse().getContentAsString(), "$.content[?(@.content.type == 'EPISODE')].id");
        String episodeEntryId = episodeEntryIds.get(0);

        mockMvc.perform(updateRequest(user, UUID.fromString(seasonEntryId), updateBody("comment", "Great season!")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/diary/" + episodeEntryId + "/deletion-impact?overrideProtectedEntries=true")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldDelete.length()").value(1))
                .andExpect(jsonPath("$.wouldDelete[0].id").value(seasonEntryId))
                .andExpect(jsonPath("$.wouldDelete[0].type").value("SEASON"))
                .andExpect(jsonPath("$.wouldDelete[0].autoGenerated").value(false))
                .andExpect(jsonPath("$.wouldDelete[0].hasReview").value(true));

        assertThat(diaryEntryRepository.findById(UUID.fromString(seasonEntryId))).isPresent();
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForDeletionImpact() throws Exception {
        mockMvc.perform(get("/diary/" + UUID.randomUUID() + "/deletion-impact"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Return NotFound - When Entry Does Not Exist")
    void shouldReturnNotFoundWhenEntryDoesNotExistOnDeletionImpact() throws Exception {
        RegisteredUser user = registerUser("deletionimpactnotfound");

        mockMvc.perform(get("/diary/" + UUID.randomUUID() + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Return NotFound - When Entry Belongs To A Different User")
    void shouldReturnNotFoundWhenEntryBelongsToADifferentUserOnDeletionImpact() throws Exception {
        RegisteredUser owner = registerUser("deletionimpactowner");
        RegisteredUser intruder = registerUser("deletionimpactintruder");
        User ownerEntity = userRepository.findById(owner.id()).orElseThrow();
        DiaryEntry entry = persistEntry(ownerEntity, persistContent("550", ContentType.MOVIE));

        mockMvc.perform(get("/diary/" + entry.getId() + "/deletion-impact")
                        .cookie(intruder.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Diary entry not found"));
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Return Empty WouldDelete And Persist Nothing - When Deleting An Episode Entry That Is Not The Bottleneck")
    void shouldReturnEmptyWouldDeleteAndPersistNothingWhenDeletingAnEpisodeEntryThatIsNotTheBottleneck() throws Exception {
        RegisteredUser user = registerUser("nonbottleneckpreview");

        mockMvc.perform(createRequest(user, episodeBody("3001", 1, 1, false, false)))
                .andExpect(status().isCreated());
        mockMvc.perform(createRequest(user, episodeBody("3001", 1, 2, true, false)))
                .andExpect(status().isCreated());

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));

        MvcResult rewatch = mockMvc.perform(createRequest(user, episodeBody("3001", 1, 1, false, false)))
                .andExpect(status().isCreated())
                .andReturn();
        String rewatchEntryId = JsonPath.read(rewatch.getResponse().getContentAsString(), "$.entry.id");

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4));

        mockMvc.perform(get("/diary/" + rewatchEntryId + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldDelete.length()").value(0));

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4));
        assertThat(diaryEntryRepository.findById(UUID.fromString(rewatchEntryId))).isPresent();
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Include The Season Entry And Persist Nothing - When Deleting The Bottleneck Episode Entry")
    void shouldIncludeTheSeasonEntryAndPersistNothingWhenDeletingTheBottleneckEpisodeEntry() throws Exception {
        RegisteredUser user = registerUser("bottleneckpreview");

        mockMvc.perform(createRequest(user, episodeBody("3005", 1, 1, false, false)))
                .andExpect(status().isCreated());
        MvcResult secondEpisode = mockMvc.perform(createRequest(user, episodeBody("3005", 1, 2, true, false)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult diaryBeforePreview = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andReturn();
        List<String> seasonEntryIdsBefore = JsonPath.read(
                diaryBeforePreview.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SEASON')].id");
        String seasonEntryId = seasonEntryIdsBefore.get(0);

        String bottleneckEpisodeId = JsonPath.read(secondEpisode.getResponse().getContentAsString(), "$.entry.id");

        mockMvc.perform(get("/diary/" + bottleneckEpisodeId + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldDelete.length()").value(1))
                .andExpect(jsonPath("$.wouldDelete[0].type").value("SEASON"));

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3));
        assertThat(diaryEntryRepository.findById(UUID.fromString(bottleneckEpisodeId))).isPresent();
        assertThat(diaryEntryRepository.findById(UUID.fromString(seasonEntryId))).isPresent();
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Include The Series Entry And Persist Nothing - When Deleting The Season Entry That Sustains Series Completion")
    void shouldIncludeTheSeriesEntryAndPersistNothingWhenDeletingTheSeasonEntryThatSustainsSeriesCompletion() throws Exception {
        RegisteredUser user = registerUser("seasonseriespreview");

        mockMvc.perform(createRequest(user, episodeBody("3010", 1, 1, true, false)))
                .andExpect(status().isCreated());
        mockMvc.perform(createRequest(user, episodeBody("3010", 2, 1, true, true)))
                .andExpect(status().isCreated());

        MvcResult diaryBeforePreview = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andReturn();

        List<String> seasonTwoEntryIds = JsonPath.read(diaryBeforePreview.getResponse().getContentAsString(),
                "$.content[?(@.content.type == 'SEASON' && @.content.seasonNumber == 2)].id");
        String seasonTwoEntryId = seasonTwoEntryIds.get(0);
        List<String> seriesEntryIds = JsonPath.read(
                diaryBeforePreview.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SERIES')].id");
        String seriesEntryId = seriesEntryIds.get(0);

        mockMvc.perform(get("/diary/" + seasonTwoEntryId + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldDelete.length()").value(1))
                .andExpect(jsonPath("$.wouldDelete[0].type").value("SERIES"));

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5));
        assertThat(diaryEntryRepository.findById(UUID.fromString(seasonTwoEntryId))).isPresent();
        assertThat(diaryEntryRepository.findById(UUID.fromString(seriesEntryId))).isPresent();
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Include Both The Season And The Series Entry - When Previewing The Deletion Of The Episode That Sustains Both")
    void shouldIncludeBothTheSeasonAndTheSeriesEntryWhenPreviewingTheDeletionOfTheEpisodeThatSustainsBoth() throws Exception {
        RegisteredUser user = registerUser("episodeseriespreview");

        mockMvc.perform(createRequest(user, episodeBody("3020", 1, 1, true, false)))
                .andExpect(status().isCreated());
        MvcResult seasonTwoEpisode = mockMvc.perform(createRequest(user, episodeBody("3020", 2, 1, true, true)))
                .andExpect(status().isCreated())
                .andReturn();

        String seasonTwoEpisodeId = JsonPath.read(seasonTwoEpisode.getResponse().getContentAsString(), "$.entry.id");

        MvcResult diaryBeforePreview = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andReturn();

        List<String> seasonTwoEntryIds = JsonPath.read(diaryBeforePreview.getResponse().getContentAsString(),
                "$.content[?(@.content.type == 'SEASON' && @.content.seasonNumber == 2)].id");
        List<String> seriesEntryIds = JsonPath.read(
                diaryBeforePreview.getResponse().getContentAsString(), "$.content[?(@.content.type == 'SERIES')].id");

        mockMvc.perform(get("/diary/" + seasonTwoEpisodeId + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldDelete.length()").value(2))
                .andExpect(jsonPath("$.wouldDelete[0].id").value(seasonTwoEntryIds.get(0)))
                .andExpect(jsonPath("$.wouldDelete[0].type").value("SEASON"))
                .andExpect(jsonPath("$.wouldDelete[0].autoGenerated").value(true))
                .andExpect(jsonPath("$.wouldDelete[1].id").value(seriesEntryIds.get(0)))
                .andExpect(jsonPath("$.wouldDelete[1].type").value("SERIES"))
                .andExpect(jsonPath("$.wouldDelete[1].autoGenerated").value(true));

        mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Remove Exactly The Entries The Preview Listed - When The Previewed Episode Is Actually Deleted")
    void shouldRemoveExactlyTheEntriesThePreviewListedWhenThePreviewedEpisodeIsActuallyDeleted() throws Exception {
        RegisteredUser user = registerUser("previewmatchesdelete");

        mockMvc.perform(createRequest(user, episodeBody("3030", 1, 1, true, false)))
                .andExpect(status().isCreated());
        MvcResult seasonTwoEpisode = mockMvc.perform(createRequest(user, episodeBody("3030", 2, 1, true, true)))
                .andExpect(status().isCreated())
                .andReturn();

        String seasonTwoEpisodeId = JsonPath.read(seasonTwoEpisode.getResponse().getContentAsString(), "$.entry.id");

        MvcResult preview = mockMvc.perform(get("/diary/" + seasonTwoEpisodeId + "/deletion-impact")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> previewedIds = JsonPath.read(preview.getResponse().getContentAsString(), "$.wouldDelete[*].id");

        mockMvc.perform(deleteRequest(user, UUID.fromString(seasonTwoEpisodeId)))
                .andExpect(status().isNoContent());

        assertThat(previewedIds).hasSize(2);
        for (String previewedId : previewedIds) {
            assertThat(diaryEntryRepository.findById(UUID.fromString(previewedId))).isEmpty();
        }

        MvcResult diaryAfterDelete = mockMvc.perform(getDiaryRequest(user, user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();
        List<String> remainingTypes = JsonPath.read(diaryAfterDelete.getResponse().getContentAsString(), "$.content[*].content.type");
        assertThat(remainingTypes).containsExactlyInAnyOrder("EPISODE", "SEASON");
    }
}