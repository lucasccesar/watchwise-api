package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SummaryControllerIntegrationTest {

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

    @MockitoBean
    private TmdbClient tmdbClient;

    @BeforeEach
    void setUp() {
        diaryEntryRepository.deleteAll();
        contentRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
        when(tmdbClient.getCardMetadata(any(), anyString())).thenReturn(new TmdbLookupResult.Unavailable<>());
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
                    "name": "%s",
                    "email": "%s@email.com",
                    "password": "Password123",
                    "isProfilePublic": %s
                }
                """.formatted(username, username, username, isProfilePublic);

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

    private MockHttpServletRequestBuilder getSummaryRequest(RegisteredUser viewer, UUID targetUserId) {
        return get("/users/" + targetUserId + "/summary").cookie(viewer.accessToken());
    }

    private Content persistContent(String tmdbId, ContentType type, Integer runtimeMinutes) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(type)
                .runtimeMinutes(runtimeMinutes)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Content persistSeries(String tmdbId) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .tmdbId(tmdbId)
                .type(ContentType.SERIES)
                .genres(java.util.List.of("Drama"))
                .releaseYear(2020)
                .countries(java.util.List.of("US"))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Content persistEpisode(String seriesTmdbId, int seasonNumber, int episodeNumber, int runtimeMinutes) {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.save(Content.builder()
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .runtimeMinutes(runtimeMinutes)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void persistEntry(User user, Content content) {
        persistEntry(user, content, null, false);
    }

    private void persistEntry(User user, Content content, Integer score, boolean watchedInTheater) {
        LocalDateTime now = LocalDateTime.now();
        diaryEntryRepository.save(DiaryEntry.builder()
                .user(user)
                .content(content)
                .score(score)
                .watchedInTheater(watchedInTheater)
                .watchedDate(LocalDate.now())
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    @DisplayName("[getSummary] Should Return WatchTime Computed From Movie Entries - When Type Is MOVIE")
    void shouldReturnWatchTimeComputedFromMovieEntriesWhenTypeIsMovie() throws Exception {
        RegisteredUser user = registerUser("summaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE, 139);
        persistEntry(entity, movie);

        mockMvc.perform(getSummaryRequest(user, user.id()).param("type", "MOVIE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchTime.totalMinutesWatched").value(139));
    }

    @Test
    @DisplayName("[getSummary] Should Return BadRequest - When Type Is Missing")
    void shouldReturnBadRequestWhenTypeIsMissing() throws Exception {
        RegisteredUser user = registerUser("summarynotype");

        mockMvc.perform(getSummaryRequest(user, user.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type must be one of: MOVIE, SERIES"));
    }

    @Test
    @DisplayName("[getSummary] Should Return BadRequest - When Type Is Not MOVIE Or SERIES")
    void shouldReturnBadRequestWhenTypeIsNotMovieOrSeries() throws Exception {
        RegisteredUser user = registerUser("summarybadtype");

        mockMvc.perform(getSummaryRequest(user, user.id()).param("type", "EPISODE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type must be one of: MOVIE, SERIES"));
    }

    @Test
    @DisplayName("[getSummary] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExist() throws Exception {
        RegisteredUser viewer = registerUser("summarynotfound");

        mockMvc.perform(getSummaryRequest(viewer, UUID.randomUUID()).param("type", "MOVIE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getSummary] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        RegisteredUser user = registerUser("summarynoauth");

        mockMvc.perform(get("/users/" + user.id() + "/summary").param("type", "MOVIE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getSummary] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("summaryforbiddenviewer");
        RegisteredUser target = registerUser("summaryforbiddentarget", false);

        mockMvc.perform(getSummaryRequest(viewer, target.id()).param("type", "MOVIE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getSummary] Should Return Ok - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnOkWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() throws Exception {
        RegisteredUser viewer = registerUser("summaryacceptedviewer");
        RegisteredUser target = registerUser("summaryacceptedtarget", false);
        persistFollow(viewer.id(), target.id(), FollowStatus.ACCEPTED);

        mockMvc.perform(getSummaryRequest(viewer, target.id()).param("type", "MOVIE"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[getHomeSummary] Should Return Totals Computed From Persisted Diary Entries - When Called")
    void shouldReturnTotalsComputedFromPersistedDiaryEntriesWhenGettingHomeSummary() throws Exception {
        RegisteredUser user = registerUser("homesummaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE, 139);
        Content episode = persistEpisode("1399", 1, 1, 55);
        persistEntry(entity, movie);
        persistEntry(entity, episode);

        mockMvc.perform(get("/users/" + user.id() + "/summary/home").cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").value(139))
                .andExpect(jsonPath("$.totalMinutesWatchedEpisodes").value(55))
                .andExpect(jsonPath("$.totalMoviesWatched").value(1))
                .andExpect(jsonPath("$.totalEpisodesWatched").value(1))
                .andExpect(jsonPath("$.distinctSeriesWatched").value(1));
    }

    @Test
    @DisplayName("[getHomeSummary] Should Keep Recently Watched Row When Card Metadata Is Unavailable")
    void shouldKeepRecentlyWatchedRowWhenCardMetadataIsUnavailable() throws Exception {
        when(tmdbClient.getCardMetadata(any(), anyString())).thenReturn(new TmdbLookupResult.Unavailable<>());

        RegisteredUser user = registerUser("homesummarycardunavailable");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("unavailable-home-card", ContentType.MOVIE, 139);
        persistEntry(entity, movie);

        mockMvc.perform(get("/users/" + user.id() + "/summary/home").cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentlyWatched").isArray())
                .andExpect(jsonPath("$.recentlyWatched[0].content.tmdbId").value("unavailable-home-card"))
                .andExpect(jsonPath("$.recentlyWatched[0].card").exists())
                .andExpect(jsonPath("$.recentlyWatched[0].card.previewStatus").value("PARTIAL"));
    }

    @Test
    @DisplayName("[getSummary] Should Keep Recent Activity Row When Card Metadata Is Unavailable")
    void shouldKeepRecentActivityRowWhenCardMetadataIsUnavailable() throws Exception {
        when(tmdbClient.getCardMetadata(any(), anyString())).thenReturn(new TmdbLookupResult.Unavailable<>());
        when(tmdbClient.getMovieFullDetails(anyString(), anyString())).thenReturn(new TmdbLookupResult.Unavailable<>());

        RegisteredUser user = registerUser("profilesummarycardunavailable");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("unavailable-profile-card", ContentType.MOVIE, 139);
        persistEntry(entity, movie, 8, false);

        mockMvc.perform(getSummaryRequest(user, user.id()).param("type", "MOVIE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentActivity").isArray())
                .andExpect(jsonPath("$.recentActivity[0].content.tmdbId").value("unavailable-profile-card"))
                .andExpect(jsonPath("$.recentActivity[0].card").exists())
                .andExpect(jsonPath("$.recentActivity[0].card.previewStatus").value("PARTIAL"));
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Return Movie And Episode Watch Time Separately - When Called")
    void shouldReturnMovieAndEpisodeWatchTimeSeparatelyWhenGettingAllTimeStats() throws Exception {
        RegisteredUser user = registerUser("alltimesummaryok");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE, 139);
        Content episode = persistEpisode("1399", 1, 1, 55);
        persistEntry(entity, movie);
        persistEntry(entity, episode);

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time").cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutesWatchedMovies").value(139))
                .andExpect(jsonPath("$.totalMinutesWatchedEpisodes").value(55));
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Return Movie Aggregates - When Type Is Movie")
    void shouldReturnMovieAggregatesWhenAllTimeEditionTypeIsMovie() throws Exception {
        RegisteredUser user = registerUser("alltimeeditionmovie");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("550", ContentType.MOVIE, 139);
        persistEntry(entity, movie, 8, true);

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time/edition")
                        .param("type", "MOVIE")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MOVIE"))
                .andExpect(jsonPath("$.watchedCount").value(1))
                .andExpect(jsonPath("$.minutesWatched").value(139))
                .andExpect(jsonPath("$.totalTheaterVisits").value(1))
                .andExpect(jsonPath("$.ratingsDistribution[0].score").value(8))
                .andExpect(jsonPath("$.ratingsDistribution[0].count").value(1))
                .andExpect(jsonPath("$.mostLoggedContent[0].count").value(1))
                .andExpect(jsonPath("$.mostLoggedContent[0].card.previewStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.topRated[0].card.previewStatus").value("PARTIAL"));

        verify(tmdbClient, never()).getMovieFullDetails(anyString(), anyString());
    }

    @Test
    @DisplayName("[getMonthInReview] Should Keep Ranking Rows And Totals When Card Metadata Is Unavailable")
    void shouldKeepMonthRankingRowsAndTotalsWhenCardMetadataIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("monthreviewcardunavailable");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("month-review-card", ContentType.MOVIE, 139);
        persistEntry(entity, movie, 8, false);

        mockMvc.perform(get("/users/" + user.id() + "/summary/month")
                        .param("type", "MOVIE")
                        .param("month", "2026-10")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchCount").value(1))
                .andExpect(jsonPath("$.minutesWatched").value(139))
                .andExpect(jsonPath("$.recentWatched[0].card.previewStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.topRated[0].card.previewStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.topLongestMovies[0].tmdbId").value("month-review-card"))
                .andExpect(jsonPath("$.topLongestMovieCards[0].previewStatus").value("PARTIAL"));

        verify(tmdbClient, never()).getMovieFullDetails(anyString(), anyString());
    }

    @Test
    @DisplayName("[getYearInReview] Should Keep Runtime Ranking Rows When Card Metadata Is Unavailable")
    void shouldKeepYearRuntimeRankingRowsWhenCardMetadataIsUnavailable() throws Exception {
        RegisteredUser user = registerUser("yearreviewcardunavailable");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content movie = persistContent("year-review-card", ContentType.MOVIE, 181);
        persistEntry(entity, movie, 9, false);

        mockMvc.perform(get("/users/" + user.id() + "/summary/year")
                        .param("type", "MOVIE")
                        .param("year", "2026")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watchCount").value(1))
                .andExpect(jsonPath("$.minutesWatched").value(181))
                .andExpect(jsonPath("$.longestWatched[0].totalMinutesWatched").value(181))
                .andExpect(jsonPath("$.longestWatched[0].card.previewStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.topRated[0].card.previewStatus").value("PARTIAL"));

        verify(tmdbClient, never()).getMovieFullDetails(anyString(), anyString());
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Deduplicate Series Titles - When Type Is Series")
    void shouldDeduplicateSeriesTitlesWhenAllTimeEditionTypeIsSeries() throws Exception {
        RegisteredUser user = registerUser("alltimeeditionseries");
        User entity = userRepository.findById(user.id()).orElseThrow();
        Content series = persistSeries("1399");
        Content firstEpisode = persistEpisode("1399", 1, 1, 55);
        Content secondEpisode = persistEpisode("1399", 1, 2, 45);
        persistEntry(entity, firstEpisode, 9, false);
        persistEntry(entity, secondEpisode, 8, false);

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time/edition")
                        .param("type", "SERIES")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SERIES"))
                .andExpect(jsonPath("$.watchedCount").value(2))
                .andExpect(jsonPath("$.minutesWatched").value(100))
                .andExpect(jsonPath("$.mostLoggedContent[0].content.id").value(series.getId().toString()))
                .andExpect(jsonPath("$.mostLoggedContent[0].count").value(2))
                .andExpect(jsonPath("$.ratingsDistribution[0].score").value(8))
                .andExpect(jsonPath("$.ratingsDistribution[0].count").value(1))
                .andExpect(jsonPath("$.ratingsDistribution[1].score").value(9))
                .andExpect(jsonPath("$.ratingsDistribution[1].count").value(1));
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Return BadRequest - When Type Is Missing")
    void shouldReturnBadRequestWhenAllTimeEditionTypeIsMissing() throws Exception {
        RegisteredUser user = registerUser("alltimeeditionnotype");

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time/edition")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type must be one of: MOVIE, SERIES"));
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Return BadRequest - When Type Is Not Movie Or Series")
    void shouldReturnBadRequestWhenAllTimeEditionTypeIsNotMovieOrSeries() throws Exception {
        RegisteredUser user = registerUser("alltimeeditionbadtype");

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time/edition")
                        .param("type", "EPISODE")
                        .cookie(user.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("type must be one of: MOVIE, SERIES"));
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenAllTimeEditionHasNoAccessTokenCookie() throws Exception {
        RegisteredUser user = registerUser("alltimeeditionnoauth");

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time/edition")
                        .param("type", "MOVIE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Return Forbidden - When Target Profile Is Private")
    void shouldReturnForbiddenWhenAllTimeEditionTargetProfileIsPrivate() throws Exception {
        RegisteredUser viewer = registerUser("alltimeeditionviewer");
        RegisteredUser target = registerUser("alltimeeditionprivate", false);

        mockMvc.perform(get("/users/" + target.id() + "/summary/all-time/edition")
                        .param("type", "MOVIE")
                        .cookie(viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }

    @Test
    @DisplayName("[getAllTimeStatsEdition] Should Return Empty Movie Edition - When User Has No Entries")
    void shouldReturnEmptyMovieEditionWhenUserHasNoEntries() throws Exception {
        RegisteredUser user = registerUser("alltimeeditionempty");

        mockMvc.perform(get("/users/" + user.id() + "/summary/all-time/edition")
                        .param("type", "MOVIE")
                        .cookie(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MOVIE"))
                .andExpect(jsonPath("$.watchedCount").value(0))
                .andExpect(jsonPath("$.minutesWatched").value(0))
                .andExpect(jsonPath("$.totalTheaterVisits").value(0))
                .andExpect(jsonPath("$.ratingsDistribution").isEmpty());
    }

    @Test
    @DisplayName("[getHomeSummary] Should Return NotFound - When Target User Does Not Exist")
    void shouldReturnNotFoundWhenTargetUserDoesNotExistForHomeSummary() throws Exception {
        RegisteredUser viewer = registerUser("homesummarynotfound");

        mockMvc.perform(get("/users/" + UUID.randomUUID() + "/summary/home").cookie(viewer.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    @DisplayName("[getHomeSummary] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForHomeSummary() throws Exception {
        RegisteredUser user = registerUser("homesummarynoauth");

        mockMvc.perform(get("/users/" + user.id() + "/summary/home"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getHomeSummary] Should Return Forbidden - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldReturnForbiddenWhenTargetProfileIsPrivateForHomeSummary() throws Exception {
        RegisteredUser viewer = registerUser("homesummaryforbiddenviewer");
        RegisteredUser target = registerUser("homesummaryforbiddentarget", false);

        mockMvc.perform(get("/users/" + target.id() + "/summary/home").cookie(viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This user profile is private"));
    }
}
