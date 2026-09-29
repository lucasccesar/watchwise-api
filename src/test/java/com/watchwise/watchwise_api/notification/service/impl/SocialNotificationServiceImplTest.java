package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialNotificationServiceImplTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-29T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    private SocialNotificationServiceImpl socialNotificationService;
    private UUID recipientId;
    private UUID targetId;
    private User recipient;
    private User joao;
    private User maria;
    private User pedro;
    private UUID insertedId;

    @BeforeEach
    void setUp() {
        socialNotificationService = new SocialNotificationServiceImpl(notificationRepository, userRepository, CLOCK);
        recipientId = UUID.randomUUID();
        targetId = UUID.randomUUID();
        recipient = user(recipientId, "Lucas");
        joao = user(UUID.randomUUID(), "Joao");
        maria = user(UUID.randomUUID(), "Maria");
        pedro = user(UUID.randomUUID(), "Pedro");
    }

    @Test
    @DisplayName("Should create one unread Like aggregate with singular message - When first actor likes target")
    void shouldCreateOneUnreadLikeAggregateWhenFirstActorLikesTarget() {
        stubNewAggregate(joao);

        socialNotificationService.notifyLikeReceived(
                joao.getId(), recipientId, NotificationTargetType.DIARY_ENTRY, targetId);

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository).insertSocialAggregateIfAbsent(
                idCaptor.capture(), eq(recipientId), eq(NotificationType.LIKE_RECEIVED.name()),
                messageCaptor.capture(), eq(joao.getId()), eq(NotificationTargetType.DIARY_ENTRY.name()),
                eq(targetId), nowCaptor.capture());

        assertThat(idCaptor.getValue()).isNotNull();
        assertThat(messageCaptor.getValue()).isEqualTo("Joao liked your review");
        assertThat(nowCaptor.getValue()).isEqualTo(now());
        verify(notificationRepository, never()).save(any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should update the same unread Like aggregate with plural message - When second actor likes target")
    void shouldUpdateSameUnreadLikeAggregateWhenSecondActorLikesTarget() {
        Notification existing = existingAggregate(UUID.randomUUID(), joao, NotificationType.LIKE_RECEIVED, false, 1,
                "Joao liked your review", now().minusMinutes(5), now().minusMinutes(5));
        stubExistingAggregate(maria, existing);

        socialNotificationService.notifyLikeReceived(
                maria.getId(), recipientId, NotificationTargetType.DIARY_ENTRY, targetId);

        ArgumentCaptor<Notification> savedCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(savedCaptor.capture());
        Notification saved = savedCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(existing.getId());
        assertThat(saved.getInteractionCount()).isEqualTo(2);
        assertThat(saved.getLatestActor()).isSameAs(maria);
        assertThat(saved.getMessage()).isEqualTo("Maria and 1 more user liked your review");
        assertThat(saved.getIsRead()).isFalse();
        assertThat(saved.getCreatedAt()).isEqualTo(now());
        assertThat(saved.getUpdatedAt()).isEqualTo(now());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Should replace read Like aggregate with a new unread id - When next actor likes target")
    void shouldReplaceReadLikeAggregateWithNewUnreadIdWhenNextActorLikesTarget() {
        UUID oldId = UUID.randomUUID();
        Notification existing = existingAggregate(oldId, joao, NotificationType.LIKE_RECEIVED, true, 4,
                "Joao and 3 more users liked your review", now().minusHours(1), now().minusHours(1));
        stubExistingAggregate(pedro, existing);

        socialNotificationService.notifyLikeReceived(
                pedro.getId(), recipientId, NotificationTargetType.DIARY_ENTRY, targetId);

        ArgumentCaptor<Notification> replacementCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).delete(existing);
        verify(notificationRepository).flush();
        verify(notificationRepository).saveAndFlush(replacementCaptor.capture());

        Notification replacement = replacementCaptor.getValue();
        assertThat(replacement.getId()).isNotEqualTo(oldId);
        assertThat(replacement.getUser()).isSameAs(recipient);
        assertThat(replacement.getType()).isEqualTo(NotificationType.LIKE_RECEIVED);
        assertThat(replacement.getTargetType()).isEqualTo(NotificationTargetType.DIARY_ENTRY);
        assertThat(replacement.getTargetId()).isEqualTo(targetId);
        assertThat(replacement.getInteractionCount()).isEqualTo(1);
        assertThat(replacement.getLatestActor()).isSameAs(pedro);
        assertThat(replacement.getMessage()).isEqualTo("Pedro liked your review");
        assertThat(replacement.getIsRead()).isFalse();
        assertThat(replacement.getCreatedAt()).isEqualTo(now());
        assertThat(replacement.getUpdatedAt()).isEqualTo(now());
    }

    @Test
    @DisplayName("Should use singular comment grammar - When first actor comments on target")
    void shouldUseSingularCommentGrammarWhenFirstActorCommentsOnTarget() {
        stubNewAggregate(joao);

        socialNotificationService.notifyCommentReceived(
                joao.getId(), recipientId, NotificationTargetType.USER_LIST, targetId);

        verify(notificationRepository).insertSocialAggregateIfAbsent(
                any(), eq(recipientId), eq(NotificationType.COMMENT_RECEIVED.name()),
                eq("Joao commented on your list"), eq(joao.getId()),
                eq(NotificationTargetType.USER_LIST.name()), eq(targetId), eq(now()));
    }

    @Test
    @DisplayName("Should use plural comment grammar - When another actor comments on target")
    void shouldUsePluralCommentGrammarWhenAnotherActorCommentsOnTarget() {
        Notification existing = existingAggregate(UUID.randomUUID(), joao, NotificationType.COMMENT_RECEIVED, false, 1,
                "Joao commented on your list", now().minusMinutes(5), now().minusMinutes(5));
        stubExistingAggregate(maria, existing);

        socialNotificationService.notifyCommentReceived(
                maria.getId(), recipientId, NotificationTargetType.USER_LIST, targetId);

        ArgumentCaptor<Notification> savedCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(savedCaptor.capture());

        assertThat(savedCaptor.getValue().getInteractionCount()).isEqualTo(2);
        assertThat(savedCaptor.getValue().getMessage()).isEqualTo("Maria and 1 more user commented on your list");
    }

    @Test
    @DisplayName("Should skip all repository work - When actor and recipient are the same user")
    void shouldSkipAllRepositoryWorkWhenActorAndRecipientAreTheSameUser() {
        socialNotificationService.notifyLikeReceived(
                recipientId, recipientId, NotificationTargetType.COMMENT, targetId);

        verifyNoInteractions(notificationRepository, userRepository);
    }

    @Test
    @DisplayName("Should refresh both timestamps - When unread aggregate is updated")
    void shouldRefreshBothTimestampsWhenUnreadAggregateIsUpdated() {
        LocalDateTime oldTime = now().minusDays(1);
        Notification existing = existingAggregate(UUID.randomUUID(), joao, NotificationType.LIKE_RECEIVED, false, 1,
                "Joao liked your review", oldTime, oldTime);
        stubExistingAggregate(maria, existing);

        socialNotificationService.notifyLikeReceived(
                maria.getId(), recipientId, NotificationTargetType.DIARY_ENTRY, targetId);

        assertThat(existing.getCreatedAt()).isEqualTo(now());
        assertThat(existing.getUpdatedAt()).isEqualTo(now());
        assertThat(existing.getCreatedAt()).isNotEqualTo(oldTime);
        assertThat(existing.getUpdatedAt()).isNotEqualTo(oldTime);
    }

    @Test
    @DisplayName("Should insert before locked lookup - When writing a social aggregate")
    void shouldInsertBeforeLockedLookupWhenWritingSocialAggregate() {
        Notification existing = existingAggregate(UUID.randomUUID(), joao, NotificationType.LIKE_RECEIVED, false, 1,
                "Joao liked your review", now(), now());
        stubExistingAggregate(maria, existing);

        socialNotificationService.notifyLikeReceived(
                maria.getId(), recipientId, NotificationTargetType.DIARY_ENTRY, targetId);

        InOrder inOrder = inOrder(notificationRepository);
        inOrder.verify(notificationRepository).insertSocialAggregateIfAbsent(
                any(), eq(recipientId), eq(NotificationType.LIKE_RECEIVED.name()), any(), eq(maria.getId()),
                eq(NotificationTargetType.DIARY_ENTRY.name()), eq(targetId), eq(now()));
        inOrder.verify(notificationRepository).findSocialAggregateForUpdate(
                eq(recipientId), eq(NotificationType.LIKE_RECEIVED),
                eq(NotificationTargetType.DIARY_ENTRY), eq(targetId));
    }

    @Test
    @DisplayName("Should Retry Insert And Locked Lookup - When Cleanup Removes Aggregate Before First Lookup")
    void shouldRetryInsertAndLockedLookupWhenCleanupRemovesAggregateBeforeFirstLookup() {
        when(userRepository.getReferenceById(joao.getId())).thenReturn(joao);
        when(notificationRepository.insertSocialAggregateIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    insertedId = invocation.getArgument(0, UUID.class);
                    return 1;
                });

        AtomicInteger lookupCount = new AtomicInteger();
        when(notificationRepository.findSocialAggregateForUpdate(
                eq(recipientId), eq(NotificationType.LIKE_RECEIVED),
                eq(NotificationTargetType.DIARY_ENTRY), eq(targetId))).thenAnswer(invocation -> {
            if (lookupCount.getAndIncrement() == 0) {
                return Optional.empty();
            }
            return Optional.of(existingAggregate(
                    insertedId, joao, NotificationType.LIKE_RECEIVED, false, 1,
                    "Joao liked your review", now(), now()));
        });

        socialNotificationService.notifyLikeReceived(
                joao.getId(), recipientId, NotificationTargetType.DIARY_ENTRY, targetId);

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository, org.mockito.Mockito.times(2)).insertSocialAggregateIfAbsent(
                idCaptor.capture(), eq(recipientId), eq(NotificationType.LIKE_RECEIVED.name()),
                eq("Joao liked your review"), eq(joao.getId()),
                eq(NotificationTargetType.DIARY_ENTRY.name()), eq(targetId), nowCaptor.capture());
        verify(notificationRepository, org.mockito.Mockito.times(2)).findSocialAggregateForUpdate(
                eq(recipientId), eq(NotificationType.LIKE_RECEIVED),
                eq(NotificationTargetType.DIARY_ENTRY), eq(targetId));

        assertThat(idCaptor.getAllValues()).doesNotHaveDuplicates();
        assertThat(nowCaptor.getAllValues()).containsExactly(now(), now());
        verify(notificationRepository, never()).save(any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    private void stubNewAggregate(User actor) {
        when(userRepository.getReferenceById(actor.getId())).thenReturn(actor);
        when(notificationRepository.insertSocialAggregateIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    insertedId = invocation.getArgument(0, UUID.class);
                    return 1;
                });
        when(notificationRepository.findSocialAggregateForUpdate(
                any(), any(), any(), any())).thenAnswer(invocation -> {
            NotificationType type = invocation.getArgument(1, NotificationType.class);
            return Optional.of(existingAggregate(insertedId, actor, type, false, 1,
                    type == NotificationType.LIKE_RECEIVED
                            ? "Joao liked your review" : "Joao commented on your list",
                    now(), now()));
        });
    }

    private void stubExistingAggregate(User actor, Notification existing) {
        when(userRepository.getReferenceById(actor.getId())).thenReturn(actor);
        when(notificationRepository.insertSocialAggregateIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(notificationRepository.findSocialAggregateForUpdate(
                eq(recipientId), eq(existing.getType()), any(), eq(targetId))).thenReturn(Optional.of(existing));
    }

    private Notification existingAggregate(
            UUID id,
            User latestActor,
            NotificationType type,
            boolean isRead,
            int interactionCount,
            String message,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        return Notification.builder()
                .id(id)
                .user(recipient)
                .type(type)
                .message(message)
                .latestActor(latestActor)
                .targetType(type == NotificationType.LIKE_RECEIVED
                        ? NotificationTargetType.DIARY_ENTRY : NotificationTargetType.USER_LIST)
                .targetId(targetId)
                .interactionCount(interactionCount)
                .isRead(isRead)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }

    private User user(UUID id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username.toLowerCase() + "@email.com")
                .password("hashed")
                .isProfilePublic(true)
                .createdAt(now())
                .updatedAt(now())
                .build();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(CLOCK.instant(), ZoneId.systemDefault());
    }
}
