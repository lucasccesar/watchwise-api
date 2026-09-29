package com.watchwise.watchwise_api.notification.repository;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("[notifications] Should Persist Legacy And Social Rows - When Both Shapes Are Valid")
    void shouldPersistLegacyAndSocialRowsWhenBothShapesAreValid() {
        Notification legacy = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now().minusMinutes(1)));
        Notification social = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationTargetType.DIARY_ENTRY, UUID.randomUUID(), LocalDateTime.now()));
        entityManager.clear();

        Page<Notification> result = notificationRepository.findByUserIdOrderByCreatedAtDesc(
                lucas.getId(), PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Notification::getId).containsExactly(social.getId(), legacy.getId());
        Notification loadedSocial = result.getContent().get(0);
        assertThat(loadedSocial.getContent()).isNull();
        assertThat(loadedSocial.getTargetType()).isEqualTo(NotificationTargetType.DIARY_ENTRY);
        assertThat(loadedSocial.getTargetId()).isEqualTo(social.getTargetId());
        assertThat(loadedSocial.getInteractionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("[notifications] Should Reject Duplicate Social Aggregate Key")
    void shouldRejectDuplicateSocialAggregateKey() {
        UUID targetId = UUID.randomUUID();
        notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationTargetType.DIARY_ENTRY, targetId, LocalDateTime.now()));

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationTargetType.DIARY_ENTRY, targetId, LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("[notifications] Should Allow Same Target UUID Across Target Types")
    void shouldAllowSameTargetUuidAcrossTargetTypes() {
        UUID targetId = UUID.randomUUID();
        Notification diaryNotification = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationTargetType.DIARY_ENTRY, targetId, LocalDateTime.now().minusMinutes(1)));
        Notification listNotification = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationTargetType.USER_LIST, targetId, LocalDateTime.now()));

        assertThat(diaryNotification.getId()).isNotEqualTo(listNotification.getId());
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("[deleteExpiredSocialNotifications] Should Delete Oldest Social Rows Up To Batch Size")
    void shouldDeleteOldestSocialRowsUpToBatchSize() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        Notification oldest = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.LIKE_RECEIVED, false, cutoff.minusSeconds(3)));
        Notification secondOldest = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.COMMENT_RECEIVED, false, cutoff.minusSeconds(2)));
        Notification remainingExpired = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.LIKE_RECEIVED, false, cutoff.minusSeconds(1)));
        Notification atCutoff = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.COMMENT_RECEIVED, false, cutoff));
        Notification[] systemNotifications = {
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.RELEASE, cutoff.minusDays(1))),
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.ANNOUNCED_DATE, cutoff.minusDays(1))),
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.CANCELLED, cutoff.minusDays(1))),
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.RENEWED, cutoff.minusDays(1))),
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.NEW_EPISODE, cutoff.minusDays(1))),
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.FOLLOWED_PERSON_NEW_CREDIT, cutoff.minusDays(1)))
        };

        int deleted = notificationRepository.deleteExpiredSocialNotifications(false, cutoff, 2);

        assertThat(deleted).isEqualTo(2);
        entityManager.clear();
        assertThat(notificationRepository.findById(oldest.getId())).isEmpty();
        assertThat(notificationRepository.findById(secondOldest.getId())).isEmpty();
        assertThat(notificationRepository.findById(remainingExpired.getId())).isPresent();
        assertThat(notificationRepository.findById(atCutoff.getId())).isPresent();
        for (Notification systemNotification : systemNotifications) {
            assertThat(notificationRepository.findById(systemNotification.getId())).isPresent();
        }
    }

    @Test
    @DisplayName("[deleteExpiredSocialNotifications] Should Use Seven-Day Read And Thirty-Day Unread Cutoffs")
    void shouldUseSevenDayReadAndThirtyDayUnreadCutoffs() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDateTime readCutoff = now.minusDays(7);
        LocalDateTime unreadCutoff = now.minusDays(30);
        Notification expiredRead = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.LIKE_RECEIVED, true, readCutoff.minusSeconds(1)));
        Notification readAtCutoff = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.COMMENT_RECEIVED, true, readCutoff));
        Notification expiredUnread = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.LIKE_RECEIVED, false, unreadCutoff.minusSeconds(1)));
        Notification unreadAtCutoff = notificationRepository.saveAndFlush(buildSocialNotification(
                NotificationType.COMMENT_RECEIVED, false, unreadCutoff));
        Notification[] systemNotifications = {
                notificationRepository.saveAndFlush(buildSystemNotification(NotificationType.RELEASE, now.minusDays(60))),
                notificationRepository.saveAndFlush(buildSystemNotification(NotificationType.ANNOUNCED_DATE, now.minusDays(60))),
                notificationRepository.saveAndFlush(buildSystemNotification(NotificationType.CANCELLED, now.minusDays(60))),
                notificationRepository.saveAndFlush(buildSystemNotification(NotificationType.RENEWED, now.minusDays(60))),
                notificationRepository.saveAndFlush(buildSystemNotification(NotificationType.NEW_EPISODE, now.minusDays(60))),
                notificationRepository.saveAndFlush(buildSystemNotification(
                        NotificationType.FOLLOWED_PERSON_NEW_CREDIT, now.minusDays(60)))
        };

        int deletedRead = notificationRepository.deleteExpiredSocialNotifications(true, readCutoff, 500);
        int deletedUnread = notificationRepository.deleteExpiredSocialNotifications(false, unreadCutoff, 500);

        assertThat(deletedRead).isEqualTo(1);
        assertThat(deletedUnread).isEqualTo(1);
        entityManager.clear();
        assertThat(notificationRepository.findById(expiredRead.getId())).isEmpty();
        assertThat(notificationRepository.findById(readAtCutoff.getId())).isPresent();
        assertThat(notificationRepository.findById(expiredUnread.getId())).isEmpty();
        assertThat(notificationRepository.findById(unreadAtCutoff.getId())).isPresent();
        for (Notification systemNotification : systemNotifications) {
            assertThat(notificationRepository.findById(systemNotification.getId())).isPresent();
        }
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

    @Test
    @DisplayName("[deleteAll] Should Cascade Delete - When The Content Is Deleted")
    void shouldCascadeDeleteWhenTheContentIsDeleted() {
        Notification notification = notificationRepository.saveAndFlush(buildNotification(LocalDateTime.now()));
        entityManager.clear();

        contentRepository.delete(contentRepository.findById(movie.getId()).orElseThrow());
        contentRepository.flush();

        assertThat(notificationRepository.findById(notification.getId())).isEmpty();
    }

    private Notification buildNotification(LocalDateTime createdAt) {
        return buildSystemNotification(NotificationType.RELEASE, createdAt);
    }

    private Notification buildSystemNotification(NotificationType type, LocalDateTime createdAt) {
        return Notification.builder()
                .user(lucas)
                .type(type)
                .message("The Matrix is out now")
                .content(movie)
                .isRead(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Notification buildSocialNotification(NotificationTargetType targetType, UUID targetId,
            LocalDateTime createdAt) {
        return Notification.builder()
                .user(lucas)
                .type(NotificationType.LIKE_RECEIVED)
                .message("Someone liked your review")
                .latestActor(lucas)
                .targetType(targetType)
                .targetId(targetId)
                .isRead(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private Notification buildSocialNotification(NotificationType type, boolean isRead, LocalDateTime updatedAt) {
        return Notification.builder()
                .user(lucas)
                .type(type)
                .message("Someone interacted with your review")
                .latestActor(lucas)
                .targetType(NotificationTargetType.DIARY_ENTRY)
                .targetId(UUID.randomUUID())
                .isRead(isRead)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }
}
