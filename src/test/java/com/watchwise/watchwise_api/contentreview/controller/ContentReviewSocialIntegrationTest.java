package com.watchwise.watchwise_api.contentreview.controller;

import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentreview.entity.ContentReview;
import com.watchwise.watchwise_api.contentreview.entity.ReviewContextKind;
import com.watchwise.watchwise_api.contentreview.entity.ReviewPublicationKind;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.platformimport.parser.impl.LetterboxdItemCsvParserImpl;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContentReviewSocialIntegrationTest {

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
    private ContentReviewRepository contentReviewRepository;

    @Autowired
    private ContentRatingRepository contentRatingRepository;

    @Autowired
    private DiaryEntryRepository diaryEntryRepository;

    @Autowired
    private ReportSubmissionRepository reportSubmissionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestThrottler requestThrottler;

    @MockitoBean
    private CompletionScopeLoader completionScopeLoader;

    @MockitoBean
    private LetterboxdItemCsvParserImpl letterboxdItemCsvParser;

    @MockitoBean
    private ContentCardAssembler contentCardAssembler;

    @BeforeEach
    void setUp() {
        RequestThrottlerTestSupport.reset(requestThrottler);
        lenient().when(contentCardAssembler.assemble(
                anyCollection(), any(ContentCardContext.class), any(ContentCardFieldSet.class))).thenReturn(Map.of());
    }

    @Test
    void writtenReviewCanBeLiked() throws Exception {
        RegisteredUser reviewer = registerUser();
        RegisteredUser liker = registerUser();
        ContentReview review = persistWrittenReview(reviewer.id());

        mockMvc.perform(post("/reviews/{reviewId}/like", review.getId())
                        .cookie(liker.accessToken(), liker.csrfToken())
                        .header("X-XSRF-TOKEN", liker.csrfToken().getValue()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "select count(*) from likes where user_id = ? and content_review_id = ?",
                Integer.class, liker.id(), review.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select target_type from notifications where user_id = ? and type = 'LIKE_RECEIVED' and target_id = ?",
                String.class, reviewer.id(), review.getId())).isEqualTo("CONTENT_REVIEW");
        mockMvc.perform(get("/notifications/view").cookie(reviewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetPreview.targetType").value("CONTENT_REVIEW"))
                .andExpect(jsonPath("$.content[0].targetPreview.targetId").value(review.getId().toString()))
                .andExpect(jsonPath("$.content[0].targetPreview.excerpt")
                        .value("A written review for social interactions"));
    }

    @Test
    void writtenReviewCommentsSupportRepliesAndNotificationPreview() throws Exception {
        RegisteredUser reviewer = registerUser();
        RegisteredUser firstCommenter = registerUser();
        RegisteredUser replier = registerUser();
        ContentReview review = persistWrittenReview(reviewer.id());

        mockMvc.perform(postComment(firstCommenter, review.getId(), "root comment", null))
                .andExpect(status().isCreated());
        UUID rootCommentId = commentId(firstCommenter.id(), review.getId(), "root comment");
        mockMvc.perform(postComment(replier, review.getId(), "reply comment", rootCommentId))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/reviews/{reviewId}/comments", review.getId())
                        .queryParam("page", "1")
                        .queryParam("size", "10")
                        .cookie(reviewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].contentReviewId").value(review.getId().toString()))
                .andExpect(jsonPath("$.content[1].text").value("reply comment"))
                .andExpect(jsonPath("$.content[1].parentCommentId").value(rootCommentId.toString()));

        String targetType = jdbc.queryForObject(
                "select target_type from notifications where user_id = ? and type = 'COMMENT_RECEIVED' and target_id = ?",
                String.class, reviewer.id(), review.getId());
        assertThat(targetType).isEqualTo("CONTENT_REVIEW");
    }

    @Test
    void ratingAndCompletionIdsAreNotReviewSocialTargets() throws Exception {
        RegisteredUser actor = registerUser();
        Content content = persistSeries();
        UUID completionId = UUID.randomUUID();
        ContentRating rating = contentRatingRepository.saveAndFlush(ContentRating.builder()
                .userId(actor.id())
                .contentId(content.getId())
                .contentType(ContentType.SERIES)
                .score(8)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        jdbc.update("""
                insert into diary_completion_projections
                    (id, user_id, content_id, content_type, watch_number, ordered_at,
                     history_revision, schedule_version)
                values (?, ?, ?, 'SERIES', 1, current_timestamp, 1, 1)
                """, completionId, actor.id(), content.getId());

        mockMvc.perform(postLike(actor, rating.getId())).andExpect(status().isNotFound());
        mockMvc.perform(postComment(actor, completionId, "not a review", null))
                .andExpect(status().isNotFound());
    }

    @Test
    void eitherBlockDirectionRejectsReviewInteractions() throws Exception {
        assertBlockedReviewInteractions(true);
        assertBlockedReviewInteractions(false);
    }

    @Test
    void blockedCommentAuthorsAreFilteredBeforePagination() throws Exception {
        RegisteredUser owner = registerUser();
        RegisteredUser viewer = registerUser();
        RegisteredUser blockedAuthor = registerUser();
        RegisteredUser visibleAuthor = registerUser();
        ContentReview review = persistWrittenReview(owner.id());

        mockMvc.perform(postComment(blockedAuthor, review.getId(), "blocked oldest comment", null))
                .andExpect(status().isCreated());
        UUID blockedCommentId = commentId(blockedAuthor.id(), review.getId(), "blocked oldest comment");
        mockMvc.perform(postComment(visibleAuthor, review.getId(), "visible later comment", null))
                .andExpect(status().isCreated());
        jdbc.update("update comments set created_at = ? where id = ?",
                LocalDateTime.now().minusDays(1), blockedCommentId);
        block(viewer.id(), blockedAuthor.id());

        mockMvc.perform(get("/reviews/{reviewId}/comments", review.getId())
                        .queryParam("page", "1")
                        .queryParam("size", "1")
                        .cookie(viewer.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].text").value("visible later comment"));
    }

    @Test
    void privateReviewIsNotExposedAndReportSnapshotSurvivesReviewDeletion() throws Exception {
        RegisteredUser owner = registerUser();
        RegisteredUser viewer = registerUser();
        ContentReview review = persistWrittenReview(owner.id());
        jdbc.update("update users set is_profile_public = false where id = ?", owner.id());

        MvcResult privateReviewComments = mockMvc.perform(get("/reviews/{reviewId}/comments", review.getId())
                        .queryParam("page", "1")
                        .queryParam("size", "10")
                        .cookie(viewer.accessToken()))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(privateReviewComments.getResponse().getContentAsString())
                .doesNotContain("A written review for social interactions");
        mockMvc.perform(postReport(viewer, "CONTENT_REVIEW", review.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewDeletionCascadesSocialRowsWithoutChangingRatingOrStoredReportSnapshot() throws Exception {
        RegisteredUser owner = registerUser();
        RegisteredUser actor = registerUser();
        ContentReview review = persistWrittenReview(owner.id());
        contentRatingRepository.saveAndFlush(ContentRating.builder()
                .userId(owner.id())
                .contentId(review.getContentId())
                .contentType(ContentType.SERIES)
                .score(6)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        mockMvc.perform(postLike(actor, review.getId())).andExpect(status().isNoContent());
        mockMvc.perform(postComment(actor, review.getId(), "snapshot review comment", null))
                .andExpect(status().isCreated());
        mockMvc.perform(postReport(actor, "CONTENT_REVIEW", review.getId()))
                .andExpect(status().isCreated());

        mockMvc.perform(deleteRequest(owner, "/reviews/" + review.getId()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from likes where content_review_id = ?",
                Integer.class, review.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from comments where content_review_id = ?",
                Integer.class, review.getId())).isZero();
        assertThat(contentRatingRepository.findByUserIdAndContentId(owner.id(), review.getContentId()))
                .get().extracting(ContentRating::getScore).isEqualTo(6);
        assertThat(reportSubmissionRepository.findAll())
                .filteredOn(submission -> submission.getReporter().getId().equals(actor.id()))
                .singleElement()
                .satisfies(submission -> assertThat(submission.getTargetSnapshot())
                        .contains("A written review for social interactions", "CONTENT_REVIEW")
                        .doesNotContain("password", "email"));
    }

    @Test
    @DisplayName("[postReport] Should Keep Review Target Types Separate - When UUID Domains Overlap")
    void shouldKeepReviewTargetTypesSeparateWhenUuidDomainsOverlap() throws Exception {
        RegisteredUser owner = registerUser();
        RegisteredUser reporter = registerUser();
        UUID sharedId = UUID.randomUUID();
        ContentReview review = persistReview(owner.id(), ReviewPublicationKind.WRITTEN_REVIEW,
                "content review text", sharedId);
        Content movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                insert into diary_entries
                    (id, user_id, content_id, watch_number, watched_date, comment,
                     auto_generated, ignore, created_at, updated_at, likes_count)
                values (?, ?, ?, 1, current_date, 'diary review text', false, false, ?, ?, 0)
                """, sharedId, owner.id(), movie.getId(), now, now);

        mockMvc.perform(postReport(reporter, "REVIEW", sharedId)).andExpect(status().isCreated());
        mockMvc.perform(postReport(reporter, "CONTENT_REVIEW", sharedId)).andExpect(status().isCreated());

        UUID missingDiaryEntryId = UUID.randomUUID();
        mockMvc.perform(postReport(reporter, "REVIEW", missingDiaryEntryId)).andExpect(status().isNotFound());

        assertThat(snapshot(reporter.id(), "REVIEW", sharedId)).contains("diary review text")
                .doesNotContain("content review text");
        assertThat(snapshot(reporter.id(), "CONTENT_REVIEW", sharedId)).contains("content review text")
                .doesNotContain("diary review text");
        assertThat(review.getId()).isEqualTo(sharedId);
    }

    private RegisteredUser registerUser() throws Exception {
        String username = "review-social-" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "name": "Review Social User",
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

    private ContentReview persistReview(
            UUID ownerId, ReviewPublicationKind kind, String text, UUID id) {
        LocalDateTime now = LocalDateTime.now();
        Content content = contentRepository.saveAndFlush(Content.builder()
                .tmdbId(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .type(ContentType.SERIES)
                .createdAt(now)
                .updatedAt(now)
                .build());
        Instant instant = Instant.now();
        ContentReview.ContentReviewBuilder builder = ContentReview.builder()
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(ContentType.SERIES)
                .publicationKind(kind)
                .contextKind(ReviewContextKind.MANUAL)
                .comment(text)
                .createdAt(instant)
                .updatedAt(instant);
        if (kind == ReviewPublicationKind.WRITTEN_REVIEW) {
            builder.watchNumber(1);
        }
        if (id != null) {
            jdbc.update("""
                    insert into content_reviews
                        (id, user_id, content_id, content_type, publication_kind, context_kind,
                         comment, watch_number, created_at, updated_at)
                    values (?, ?, ?, 'SERIES', ?, 'MANUAL', ?, ?, ?, ?)
                """, id, ownerId, content.getId(), kind.name(), text,
                    kind == ReviewPublicationKind.WRITTEN_REVIEW ? 1 : null,
                    Timestamp.from(instant), Timestamp.from(instant));
            return contentReviewRepository.findById(id).orElseThrow();
        }
        return contentReviewRepository.saveAndFlush(builder.build());
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

    private MockHttpServletRequestBuilder postLike(RegisteredUser actor, UUID reviewId) {
        return post("/reviews/{reviewId}/like", reviewId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder postComment(
            RegisteredUser actor, UUID reviewId, String text, UUID parentCommentId) {
        String parent = parentCommentId == null ? "null" : "\"" + parentCommentId + "\"";
        return post("/reviews/{reviewId}/comments", reviewId)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"text":"%s","parentCommentId":%s,"containsSpoiler":false}
                        """.formatted(text, parent));
    }

    private MockHttpServletRequestBuilder postReport(
            RegisteredUser actor, String targetType, UUID targetId) {
        return post("/reports")
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"targetType":"%s","targetId":"%s","reason":"OTHER"}
                        """.formatted(targetType, targetId));
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser actor, String path) {
        return delete(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private UUID commentId(UUID userId, UUID reviewId, String text) {
        return jdbc.queryForObject(
                "select id from comments where user_id = ? and content_review_id = ? and text = ?",
                UUID.class, userId, reviewId, text);
    }

    private void assertBlockedReviewInteractions(boolean actorBlocksOwner) throws Exception {
        RegisteredUser owner = registerUser();
        RegisteredUser actor = registerUser();
        ContentReview review = persistWrittenReview(owner.id());
        if (actorBlocksOwner) {
            block(actor.id(), owner.id());
        } else {
            block(owner.id(), actor.id());
        }

        mockMvc.perform(postLike(actor, review.getId())).andExpect(status().isForbidden());
        mockMvc.perform(postComment(actor, review.getId(), "blocked comment", null))
                .andExpect(status().isForbidden());
    }

    private void block(UUID blockerId, UUID blockedId) {
        jdbc.update("insert into user_blocks (id, blocker_id, blocked_id, created_at) values (?, ?, ?, ?)",
                UUID.randomUUID(), blockerId, blockedId, LocalDateTime.now());
    }

    private String snapshot(UUID reporterId, String targetType, UUID targetId) {
        return jdbc.queryForObject("""
                select submission.target_snapshot
                from report_submissions submission
                join report_cases report_case on report_case.id = submission.case_id
                where submission.reporter_id = ? and report_case.target_type = ? and report_case.target_id = ?
                """, String.class, reporterId, targetType, targetId);
    }

    private ContentReview persistWrittenReview(UUID ownerId) {
        LocalDateTime now = LocalDateTime.now();
        Content content = contentRepository.saveAndFlush(Content.builder()
                .tmdbId(UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .type(ContentType.SERIES)
                .createdAt(now)
                .updatedAt(now)
                .build());
        Instant instant = Instant.now();
        return contentReviewRepository.saveAndFlush(ContentReview.builder()
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(ContentType.SERIES)
                .publicationKind(ReviewPublicationKind.WRITTEN_REVIEW)
                .contextKind(ReviewContextKind.MANUAL)
                .comment("A written review for social interactions")
                .watchNumber(1)
                .createdAt(instant)
                .updatedAt(instant)
                .build());
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }
}
