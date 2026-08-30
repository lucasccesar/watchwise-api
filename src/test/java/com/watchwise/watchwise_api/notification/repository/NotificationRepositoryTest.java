package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class NotificationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private User lucas;
    private Content movie;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();

        lucas = userRepository.saveAndFlush(User.builder()
                .username("lucas").email("lucas@email.com").password("hashed")
                .profilePicture("https://example.com/p.png").isProfilePublic(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        movie = contentRepository.saveAndFlush(Content.builder()
                .tmdbId("603").type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
    }

    @Test
    @DisplayName("[findByUserIdOrderByCreatedAtDesc] Should Return Notifications Newest First - When Multiple Exist")
    void shouldReturnNotificationsNewestFirstWhenMultipleExist() {
        Notification older = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now().minusDays(1)));
        Notification newer = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        entityManager.clear();

        Page<Notification> result = notificationRepository.findByUserIdOrderByCreatedAtDesc(lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Notification::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("[findByUserIdAndIsReadOrderByCreatedAtDesc] Should Return Only Unread - When isRead Filter Is False")
    void shouldReturnOnlyUnreadWhenIsReadFilterIsFalse() {
        Notification unread = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        Notification read = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        read.setIsRead(true);
        notificationRepository.saveAndFlush(read);
        entityManager.clear();

        Page<Notification> result = notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(lucas.getId(), false, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Notification::getId).containsExactly(unread.getId());
    }

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete - When The User Is Deleted")
    void shouldCascadeDeleteWhenTheUserIsDeleted() {
        Notification notification = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        entityManager.clear();

        userRepository.delete(userRepository.findById(lucas.getId()).orElseThrow());
        userRepository.flush();

        assertThat(notificationRepository.findById(notification.getId())).isEmpty();
    }

    private Notification buildNotification(LocalDateTime createdAt) {
        return Notification.builder()
                .user(lucas)
                .type(NotificationType.RELEASE)
                .message("The Matrix is out now")
                .content(movie)
                .isRead(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }
}
