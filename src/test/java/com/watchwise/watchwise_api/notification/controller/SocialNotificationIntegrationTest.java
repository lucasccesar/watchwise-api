package com.watchwise.watchwise_api.notification.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.like.repository.LikeRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

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
class SocialNotificationIntegrationTest {

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
    private CommentRepository commentRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    @BeforeEach
    void setUp() {
        likeRepository.deleteAll();
        commentRepository.deleteAll();
        diaryEntryRepository.deleteAll();
        notificationRepository.deleteAll();
        contentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    @Test
    @DisplayName("[social notifications] Should Aggregate Likes And Replace The Read Aggregate")
    void shouldAggregateLikesAndReplaceTheReadAggregate() throws Exception {
        RegisteredUser owner = registerUser("socialownerlike");
        RegisteredUser actorOne = registerUser("socialactorlikeone");
        RegisteredUser actorTwo = registerUser("socialactorliketwo");
        RegisteredUser actorThree = registerUser("socialactorlikethree");
        DiaryEntry review = persistReview(owner.id());

        mockMvc.perform(postRequest(actorOne, "/diary/" + review.getId() + "/like"))
                .andExpect(status().isNoContent());

        Notification first = findSocialNotification(owner.id(), NotificationType.LIKE_RECEIVED, review.getId());
        assertThat(first.getInteractionCount()).isEqualTo(1);
        assertThat(first.getIsRead()).isFalse();
        assertThat(first.getLatestActor().getId()).isEqualTo(actorOne.id());

        mockMvc.perform(get("/notifications").cookie(owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].latestActor.username").value("socialactorlikeone"))
                .andExpect(jsonPath("$.content[0].targetType").value("DIARY_ENTRY"))
                .andExpect(jsonPath("$.content[0].targetId").value(review.getId().toString()))
                .andExpect(jsonPath("$.content[0].interactionCount").value(1))
                .andExpect(jsonPath("$.content[0].content").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/notifications").cookie(actorOne.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(postRequest(actorTwo, "/diary/" + review.getId() + "/like"))
                .andExpect(status().isNoContent());

        Notification aggregated = findSocialNotification(owner.id(), NotificationType.LIKE_RECEIVED, review.getId());
        assertThat(aggregated.getId()).isEqualTo(first.getId());
        assertThat(aggregated.getInteractionCount()).isEqualTo(2);
        assertThat(aggregated.getLatestActor().getId()).isEqualTo(actorTwo.id());
        assertThat(aggregated.getMessage()).isEqualTo("socialactorliketwo and 1 more user liked your review");

        mockMvc.perform(deleteRequest(actorTwo, "/diary/" + review.getId() + "/like"))
                .andExpect(status().isNoContent());
        assertThat(findSocialNotification(owner.id(), NotificationType.LIKE_RECEIVED, review.getId())
                .getInteractionCount()).isEqualTo(2);

        mockMvc.perform(patch("/notifications/{id}/read", first.getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isNoContent());

        mockMvc.perform(postRequest(actorThree, "/diary/" + review.getId() + "/like"))
                .andExpect(status().isNoContent());

        Notification replacement = findSocialNotification(owner.id(), NotificationType.LIKE_RECEIVED, review.getId());
        assertThat(replacement.getId()).isNotNull();
        assertThat(replacement.getId()).isNotEqualTo(first.getId());
        assertThat(replacement.getInteractionCount()).isEqualTo(1);
        assertThat(replacement.getIsRead()).isFalse();
        assertThat(replacement.getLatestActor().getId()).isEqualTo(actorThree.id());
    }

    @Test
    @DisplayName("[social notifications] Should Aggregate Comments And Replace The Read Aggregate")
    void shouldAggregateCommentsAndReplaceTheReadAggregate() throws Exception {
        RegisteredUser owner = registerUser("socialownercomment");
        RegisteredUser actorOne = registerUser("socialactorcommentone");
        RegisteredUser actorTwo = registerUser("socialactorcommenttwo");
        RegisteredUser actorThree = registerUser("socialactorcommentthree");
        DiaryEntry review = persistReview(owner.id());

        mockMvc.perform(postRequest(actorOne, "/diary/" + review.getId() + "/comments", commentBody("First comment")))
                .andExpect(status().isCreated());
        Notification first = findSocialNotification(owner.id(), NotificationType.COMMENT_RECEIVED, review.getId());

        mockMvc.perform(postRequest(actorTwo, "/diary/" + review.getId() + "/comments", commentBody("Second comment")))
                .andExpect(status().isCreated());

        Notification aggregated = findSocialNotification(owner.id(), NotificationType.COMMENT_RECEIVED, review.getId());
        assertThat(aggregated.getId()).isEqualTo(first.getId());
        assertThat(aggregated.getInteractionCount()).isEqualTo(2);
        assertThat(aggregated.getLatestActor().getId()).isEqualTo(actorTwo.id());
        assertThat(aggregated.getMessage()).isEqualTo("socialactorcommenttwo and 1 more user commented on your review");

        mockMvc.perform(patch("/notifications/{id}/read", first.getId())
                        .cookie(owner.accessToken(), owner.csrfToken())
                        .header("X-XSRF-TOKEN", owner.csrfToken().getValue()))
                .andExpect(status().isNoContent());

        mockMvc.perform(postRequest(actorThree, "/diary/" + review.getId() + "/comments", commentBody("Third comment")))
                .andExpect(status().isCreated());

        Notification replacement = findSocialNotification(owner.id(), NotificationType.COMMENT_RECEIVED, review.getId());
        assertThat(replacement.getId()).isNotNull();
        assertThat(replacement.getId()).isNotEqualTo(first.getId());
        assertThat(replacement.getInteractionCount()).isEqualTo(1);
        assertThat(replacement.getIsRead()).isFalse();
        assertThat(replacement.getLatestActor().getId()).isEqualTo(actorThree.id());
    }

    @Test
    @DisplayName("[social notifications] Should Suppress Self Like And Comment Notifications")
    void shouldSuppressSelfLikeAndCommentNotifications() throws Exception {
        RegisteredUser owner = registerUser("socialselfowner");
        DiaryEntry review = persistReview(owner.id());

        mockMvc.perform(postRequest(owner, "/diary/" + review.getId() + "/like"))
                .andExpect(status().isNoContent());
        mockMvc.perform(postRequest(owner, "/diary/" + review.getId() + "/comments", commentBody("Self comment")))
                .andExpect(status().isCreated());

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    private RegisteredUser registerUser(String username) throws Exception {
        String body = """
                {
                    "username": "%s",
                    "name": "%s","username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123"
                }
                """.formatted(username, username, username);

        MvcResult result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        Cookie accessTokenCookie = result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE);
        Cookie csrfCookie = result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE);
        assertThat(accessTokenCookie).isNotNull();
        assertThat(csrfCookie).isNotNull();

        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new RegisteredUser(user.getId(), accessTokenCookie, csrfCookie);
    }

    private DiaryEntry persistReview(UUID ownerId) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        Content content = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603")
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return diaryEntryRepository.saveAndFlush(DiaryEntry.builder()
                .user(owner)
                .content(content)
                .score(8)
                .comment("Persisted review")
                .watchNumber(1)
                .watchedDate(java.time.LocalDate.now())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private Notification findSocialNotification(UUID recipientId, NotificationType type, UUID targetId) {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getUser().getId().equals(recipientId))
                .filter(notification -> notification.getType() == type)
                .filter(notification -> notification.getTargetType() == NotificationTargetType.DIARY_ENTRY)
                .filter(notification -> notification.getTargetId().equals(targetId))
                .findFirst()
                .orElseThrow();
    }

    private MockHttpServletRequestBuilder postRequest(RegisteredUser actor, String path) {
        return post(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private MockHttpServletRequestBuilder postRequest(RegisteredUser actor, String path, String body) {
        return postRequest(actor, path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder deleteRequest(RegisteredUser actor, String path) {
        return delete(path)
                .cookie(actor.accessToken(), actor.csrfToken())
                .header("X-XSRF-TOKEN", actor.csrfToken().getValue());
    }

    private String commentBody(String text) {
        return """
                {
                    "text": "%s",
                    "parentCommentId": null,
                    "containsSpoiler": false
                }
                """.formatted(text);
    }
}
