package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowNotificationServiceImplTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-13T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserBlockAccessService userBlockAccessService;

    private FollowNotificationServiceImpl followNotificationService;
    private User requester;
    private User owner;
    private User secondRequester;

    @BeforeEach
    void setUp() {
        followNotificationService = new FollowNotificationServiceImpl(notificationRepository, CLOCK, userBlockAccessService);
        requester = user("lucas");
        owner = user("marina");
        secondRequester = user("joao");
    }

    @Test
    void shouldCreateFollowRequestNotificationForProfileOwner() {
        followNotificationService.notifyFollowRequested(requester, owner);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification notification = captor.getValue();

        assertThat(notification.getUser()).isSameAs(owner);
        assertThat(notification.getLatestActor()).isSameAs(requester);
        assertThat(notification.getType()).isEqualTo(NotificationType.FOLLOW_REQUEST);
        assertThat(notification.getMessage()).isEqualTo("lucas requested to follow you");
        assertThat(notification.getIsRead()).isFalse();
        assertThat(notification.getCreatedAt()).isEqualTo(now());
        assertThat(notification.getUpdatedAt()).isEqualTo(now());
        assertThat(notification.getContent()).isNull();
        assertThat(notification.getTargetType()).isNull();
        assertThat(notification.getTargetId()).isNull();
    }

    @Test
    void shouldSuppressFollowNotificationWhenRequesterAndOwnerAreBlocked() {
        when(userBlockAccessService.isBlockedEitherDirection(requester.getId(), owner.getId())).thenReturn(true);

        followNotificationService.notifyFollowRequested(requester, owner);

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void shouldCreateNewFollowerNotificationForPublicProfileOwner() {
        followNotificationService.notifyNewFollower(requester, owner);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        assertThat(captor.getValue().getUser()).isSameAs(owner);
        assertThat(captor.getValue().getLatestActor()).isSameAs(requester);
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.NEW_FOLLOWER);
        assertThat(captor.getValue().getMessage()).isEqualTo("lucas started following you");
    }

    @Test
    void shouldCreateOneAcceptanceNotificationPerRequesterInOneBatch() {
        followNotificationService.notifyFollowRequestsAccepted(owner, List.of(requester, secondRequester));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(captor.capture());
        List<Notification> notifications = captor.getValue();

        assertThat(notifications).hasSize(2);
        assertThat(notifications).extracting(Notification::getUser)
                .containsExactly(requester, secondRequester);
        assertThat(notifications).allSatisfy(notification -> {
            assertThat(notification.getLatestActor()).isSameAs(owner);
            assertThat(notification.getType()).isEqualTo(NotificationType.FOLLOW_REQUEST_ACCEPTED);
            assertThat(notification.getMessage()).isEqualTo("marina accepted your follow request");
            assertThat(notification.getIsRead()).isFalse();
            assertThat(notification.getCreatedAt()).isEqualTo(now());
            assertThat(notification.getContent()).isNull();
            assertThat(notification.getTargetType()).isNull();
            assertThat(notification.getTargetId()).isNull();
        });
    }

    private User user(String username) {
        return User.builder().username(username).build();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(FIXED_INSTANT, ZoneId.systemDefault());
    }
}
