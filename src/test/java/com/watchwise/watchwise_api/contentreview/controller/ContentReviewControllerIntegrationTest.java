package com.watchwise.watchwise_api.contentreview.controller;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.entity.DiaryCompletionProjection;
import com.watchwise.watchwise_api.diarycompletion.entity.UserSeriesHistoryState;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.EpisodeCoordinate;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.repository.DiaryCompletionProjectionRepository;
import com.watchwise.watchwise_api.diarycompletion.repository.UserSeriesHistoryStateRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinion;
import com.watchwise.watchwise_api.contentreview.service.HistoricalOpinionRestoreService;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContentReviewControllerIntegrationTest {

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
    private ContentRatingRepository contentRatingRepository;

    @Autowired
    private ContentReviewRepository contentReviewRepository;

    @Autowired
    private HistoricalOpinionRestoreService historicalOpinionRestoreService;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private DiaryCompletionProjectionRepository projectionRepository;

    @Autowired
    private UserSeriesHistoryStateRepository historyStateRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoBean
    private LetterboxdItemCsvParserImpl letterboxdItemCsvParser;

    @Test
    void firstRatingWithoutWatchedEpisodeEvidenceIsRejected() throws Exception {
        RegisteredUser owner = registerUser();
        Content series = persistSeries();

        mockMvc.perform(put("/contents/{contentId}/my-rating", series.getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), series.getId())).isEmpty();
    }

    @Test
    void completedSeriesReviewPublishesItsScoreAsTheCurrentRating() throws Exception {
        RegisteredUser owner = registerUser();
        ReadyContent ready = persistReadyContent(owner, ContentType.SERIES);

        mockMvc.perform(post("/contents/{contentId}/reviews", ready.target().getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .header("Idempotency-Key", "series-review-publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"A complete review\",\"score\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoreAtPublication").value(7))
                .andExpect(jsonPath("$.watchNumber").value(1));

        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), ready.target().getId()))
                .get()
                .extracting(rating -> rating.getScore())
                .isEqualTo(7);
        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(
                owner.id(), ready.target().getId())).hasSize(1);
    }

    @Test
    void newestScoredReviewOverridesAnExistingManualRating() throws Exception {
        RegisteredUser owner = registerUser();
        ReadyContent ready = persistReadyContent(owner, ContentType.SERIES);
        Instant now = Instant.now();
        ContentRating manualRating = contentRatingRepository.saveAndFlush(ContentRating.builder()
                .userId(owner.id())
                .contentId(ready.target().getId())
                .contentType(ContentType.SERIES)
                .score(8)
                .createdAt(now)
                .updatedAt(now)
                .build());

        mockMvc.perform(reviewRequest(owner, ready.target().getId(), "newest-review-overrides-rating",
                        "{\"comment\":\"Newest scored review\",\"score\":7,\"expectedRatingVersion\":%d}"
                                .formatted(manualRating.getVersion())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scoreAtPublication").value(7));

        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), ready.target().getId()))
                .get()
                .extracting(ContentRating::getScore)
                .isEqualTo(7);
    }

    @Test
    void reviewPublicationReplaysSameKeyAndRejectsDifferentPayload() throws Exception {
        RegisteredUser owner = registerUser();
        ReadyContent ready = persistReadyContent(owner, ContentType.SERIES);

        MvcResult first = mockMvc.perform(reviewRequest(owner, ready.target().getId(), "review-replay-key",
                        "{\"comment\":\"Same payload\",\"score\":7}"))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(reviewRequest(owner, ready.target().getId(), "review-replay-key",
                        "{\"comment\":\"Same payload\",\"score\":7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(firstId));

        mockMvc.perform(reviewRequest(owner, ready.target().getId(), "review-replay-key",
                        "{\"comment\":\"Different payload\",\"score\":7}"))
                .andExpect(status().isConflict());

        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(
                owner.id(), ready.target().getId())).hasSize(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from user_action_receipts where user_id = ? and operation = 'CONTENT_REVIEW_PUBLICATION'",
                Integer.class,
                owner.id())).isEqualTo(1);
    }

    @Test
    void seasonAllowsOneWrittenReviewPerPassWhileSeriesAllowsSeveral() throws Exception {
        RegisteredUser owner = registerUser();
        ReadyContent season = persistReadyContent(owner, ContentType.SEASON);

        mockMvc.perform(reviewRequest(owner, season.target().getId(), "season-review-one",
                        "{\"comment\":\"Season review\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(reviewRequest(owner, season.target().getId(), "season-review-two",
                        "{\"comment\":\"Duplicate season pass\"}"))
                .andExpect(status().isConflict());
        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(
                owner.id(), season.target().getId())).hasSize(1);

        ReadyContent series = persistReadyContent(owner, ContentType.SERIES);
        mockMvc.perform(reviewRequest(owner, series.target().getId(), "series-review-one",
                        "{\"comment\":\"First series review\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(reviewRequest(owner, series.target().getId(), "series-review-two",
                        "{\"comment\":\"Second series review\"}"))
                .andExpect(status().isCreated());
        assertThat(contentReviewRepository.findByUserIdAndContentIdOrderByCreatedAtDesc(
                owner.id(), series.target().getId())).hasSize(2);
    }

    @Test
    void ratingRequiresFreshExpectedVersionOnceRatingAlreadyExists() throws Exception {
        RegisteredUser owner = registerUser();
        ReadyContent ready = persistReadyContent(owner, ContentType.SERIES);
        Instant now = Instant.now();
        ContentRating initial = contentRatingRepository.saveAndFlush(ContentRating.builder()
                .userId(owner.id())
                .contentId(ready.target().getId())
                .contentType(ContentType.SERIES)
                .score(8)
                .createdAt(now)
                .updatedAt(now)
                .build());

        mockMvc.perform(ratingRequest(owner, ready.target().getId(), "{\"score\":9,\"expectedVersion\":%d}"
                        .formatted(initial.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(9));
        mockMvc.perform(ratingRequest(owner, ready.target().getId(), "{\"score\":5,\"expectedVersion\":%d}"
                        .formatted(initial.getVersion())))
                .andExpect(status().isConflict());
        mockMvc.perform(ratingRequest(owner, ready.target().getId(), "{\"score\":5}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reviewIsHiddenFromOtherOwnersAndDeletingItLeavesRatingUntouched() throws Exception {
        RegisteredUser owner = registerUser();
        RegisteredUser other = registerUser();
        ReadyContent ready = persistReadyContent(owner, ContentType.SERIES);
        String createdBody = mockMvc.perform(reviewRequest(owner, ready.target().getId(), "review-owner-key",
                        "{\"comment\":\"Owned review\",\"score\":7}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID reviewId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(createdBody, "$.id"));

        mockMvc.perform(get("/reviews/{reviewId}", reviewId).cookie(other.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/reviews/{reviewId}", reviewId).cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreAtPublication").value(7));
        mockMvc.perform(patch("/reviews/{reviewId}", reviewId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Edited text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoreAtPublication").value(7))
                .andExpect(jsonPath("$.comment").value("Edited text"));

        mockMvc.perform(delete("/reviews/{reviewId}", reviewId)
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isNoContent());
        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), ready.target().getId()))
                .get()
                .extracting(ContentRating::getScore)
                .isEqualTo(7);
    }

    @Test
    void reviewEndpointsRequireAuthenticationAndRatingMutationsRequireCsrf() throws Exception {
        mockMvc.perform(get("/reviews/{reviewId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        RegisteredUser owner = registerUser();
        Content series = persistSeries();
        mockMvc.perform(put("/contents/{contentId}/my-rating", series.getId())
                        .cookie(owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":7}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void historicalRestoreValidatesPersistedPassEventsAndPreservesSourceDay() throws Exception {
        RegisteredUser owner = registerUser();
        ReadyContent ready = persistReadyContent(owner, ContentType.SERIES);
        MediaReference reference = MediaReference.series(ready.series().getTmdbId());
        Instant validUntil = Instant.now().plusSeconds(3600);
        when(completionScopeLoader.load(eq(reference), eq("US"), eq("en-US"), any(Instant.class)))
                .thenReturn(new VersionedCompletionScope(
                        new CompletionScope(reference, Set.of(new EpisodeCoordinate(1, 1)), true),
                        "historical-scope-hash",
                        validUntil));
        HistoricalOpinion opinion = new HistoricalOpinion(
                reference,
                1,
                "Restored historical review",
                7,
                null,
                LocalDate.parse("2024-04-05"),
                "{\"source\":\"verified\"}");
        HistoricalViewingEvidence evidence = new HistoricalViewingEvidence(
                reference, 1, "historical-scope-hash", Set.of(ready.event().getId()));

        UUID reviewId = historicalOpinionRestoreService.restore(owner.id(), opinion, evidence).orElseThrow();

        ContentReview restored = contentReviewRepository.findById(reviewId).orElseThrow();
        assertThat(restored.getContextKind()).isEqualTo(com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind.COMPLETION);
        assertThat(restored.getSourcePublishedAt()).isNull();
        assertThat(restored.getSourcePublishedOn()).isEqualTo(LocalDate.parse("2024-04-05"));
        assertThat(restored.getWatchNumber()).isEqualTo(1);
        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), ready.series().getId()))
                .get()
                .extracting(ContentRating::getScore)
                .isEqualTo(7);
    }

    private RegisteredUser registerUser() throws Exception {
        String username = "opinion" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "name": "Opinion Owner",
                                  "email": "%s@example.com",
                                  "password": "Password123",
                                  "isProfilePublic": true
                                }
                                """.formatted(username, username)))
                .andExpect(status().isCreated())
                .andReturn();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(
                user.getId(),
                result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE),
                result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    private Content persistSeries() {
        LocalDateTime now = LocalDateTime.now();
        return contentRepository.saveAndFlush(Content.builder()
                .tmdbId(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .type(ContentType.SERIES)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reviewRequest(
            RegisteredUser owner,
            UUID contentId,
            String idempotencyKey,
            String body) {
        return post("/contents/{contentId}/reviews", contentId)
                .cookie(owner.accessToken(), owner.csrfToken())
                .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ratingRequest(
            RegisteredUser owner,
            UUID contentId,
            String body) {
        return put("/contents/{contentId}/my-rating", contentId)
                .cookie(owner.accessToken(), owner.csrfToken())
                .header("X-XSRF-TOKEN", owner.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private ReadyContent persistReadyContent(RegisteredUser owner, ContentType targetType) {
        User user = userRepository.findById(owner.id()).orElseThrow();
        Content series = persistSeries();
        LocalDateTime now = LocalDateTime.now();
        Content target = targetType == ContentType.SERIES
                ? series
                : contentRepository.saveAndFlush(Content.builder()
                        .type(ContentType.SEASON)
                        .seriesTmdbId(series.getTmdbId())
                        .seasonNumber(1)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        Content episode = contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.EPISODE)
                .seriesTmdbId(series.getTmdbId())
                .seasonNumber(1)
                .episodeNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        DiaryEntry event = diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(user)
                .content(episode)
                .watchNumber(1)
                .watchedDate(LocalDate.now().minusDays(1))
                .autoGenerated(false)
                .ignore(false)
                .createdAt(now)
                .updatedAt(now)
                .likesCount(0)
                .build());
        historyStateRepository.saveAndFlush(UserSeriesHistoryState.builder()
                .id(new UserSeriesHistoryState.Key(owner.id(), series.getTmdbId()))
                .historyRevision(1L)
                .computedRevision(1L)
                .scheduleVersion(0L)
                .validUntil(Instant.now().plusSeconds(3600))
                .build());
        projectionRepository.saveAndFlush(DiaryCompletionProjection.builder()
                .userId(owner.id())
                .contentId(target.getId())
                .contentType(targetType)
                .watchNumber(1)
                .anchorDiaryEntryId(event.getId())
                .completedOn(LocalDate.now().minusDays(1))
                .orderedAt(Instant.now())
                .historyRevision(1L)
                .scheduleVersion(0L)
                .validUntil(Instant.now().plusSeconds(3600))
                .build());
        return new ReadyContent(series, target, episode, event);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private record ReadyContent(Content series, Content target, Content episode, DiaryEntry event) {
    }
}
