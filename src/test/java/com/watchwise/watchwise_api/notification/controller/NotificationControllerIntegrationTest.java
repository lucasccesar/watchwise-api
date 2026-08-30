package com.watchwise.watchwise_api.notification.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationControllerIntegrationTest {

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
    private NotificationRepository notificationRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RequestThrottler requestThrottler;

    private Content movie;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        notificationRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);

        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    private record RegisteredUser(UUID id, Cookie accessToken, Cookie csrfToken) {
    }

    private RegisteredUser registerUser(String username) throws Exception {
        String body = """
                {
                    "username": "%s",
                    "email": "%s@email.com",
                    "password": "Password123",
                    "isProfilePublic": true
                }
                """.formatted(username, username);

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

    private Notification buildNotification(UUID ownerId) {
        User owner = userRepository.findById(ownerId).orElseThrow();
        return Notification.builder()
                .user(owner).type(NotificationType.RELEASE).message("The Matrix is out now")
                .content(movie).isRead(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("[getNotifications] Should Return Only The Caller's Notifications - When Authenticated")
    void shouldReturnOnlyTheCallersNotificationsWhenAuthenticated() throws Exception {
        RegisteredUser lucas = registerUser("notifok");
        RegisteredUser marina = registerUser("notifother");
        notificationRepository.saveAndFlush(buildNotification(lucas.id()));
        notificationRepository.saveAndFlush(buildNotification(marina.id()));

        mockMvc.perform(get("/notifications").cookie(lucas.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("[getNotifications] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresent() throws Exception {
        mockMvc.perform(get("/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[getNotifications] Should Return BadRequest - When Page Is Negative")
    void shouldReturnBadRequestWhenPageIsNegative() throws Exception {
        RegisteredUser lucas = registerUser("notifbadpage");

        mockMvc.perform(get("/notifications").param("page", "-1").cookie(lucas.accessToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[markAsRead] Should Return NoContent And Persist isRead True - When Notification Belongs To The Caller")
    void shouldReturnNoContentAndPersistIsReadTrueWhenNotificationBelongsToTheCaller() throws Exception {
        RegisteredUser lucas = registerUser("notifreadok");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(lucas.accessToken(), lucas.csrfToken())
                        .header("X-XSRF-TOKEN", lucas.csrfToken().getValue()))
                .andExpect(status().isNoContent());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getIsRead()).isTrue();
    }

    @Test
    @DisplayName("[markAsRead] Should Return Unauthorized - When No Access Token Cookie Is Present")
    void shouldReturnUnauthorizedWhenNoAccessTokenCookieIsPresentForMarkAsRead() throws Exception {
        RegisteredUser lucas = registerUser("notifreadnoauth");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(lucas.csrfToken())
                        .header("X-XSRF-TOKEN", lucas.csrfToken().getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("[markAsRead] Should Return Forbidden - When Csrf Token Is Missing")
    void shouldReturnForbiddenWhenCsrfTokenIsMissing() throws Exception {
        RegisteredUser lucas = registerUser("notifreadnocsrf");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(lucas.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[markAsRead] Should Return NotFound - When Notification Does Not Exist")
    void shouldReturnNotFoundWhenNotificationDoesNotExist() throws Exception {
        RegisteredUser lucas = registerUser("notifreadnotfound");

        mockMvc.perform(patch("/notifications/{id}/read", UUID.randomUUID())
                        .cookie(lucas.accessToken(), lucas.csrfToken())
                        .header("X-XSRF-TOKEN", lucas.csrfToken().getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("[markAsRead] Should Return Forbidden - When Notification Belongs To A Different User")
    void shouldReturnForbiddenWhenNotificationBelongsToADifferentUser() throws Exception {
        RegisteredUser lucas = registerUser("notifreadowner");
        RegisteredUser marina = registerUser("notifreadintruder");
        Notification notification = notificationRepository.saveAndFlush(buildNotification(lucas.id()));

        mockMvc.perform(patch("/notifications/{id}/read", notification.getId())
                        .cookie(marina.accessToken(), marina.csrfToken())
                        .header("X-XSRF-TOKEN", marina.csrfToken().getValue()))
                .andExpect(status().isForbidden());

        assertThat(notificationRepository.findById(notification.getId()).orElseThrow().getIsRead()).isFalse();
    }
}
