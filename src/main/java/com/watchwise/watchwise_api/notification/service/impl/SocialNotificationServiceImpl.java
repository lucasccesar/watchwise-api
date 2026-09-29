package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.SocialNotificationService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SocialNotificationServiceImpl implements SocialNotificationService {

    private static final int MAX_AGGREGATE_ATTEMPTS = 2;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void notifyLikeReceived(
            UUID actorId,
            UUID recipientId,
            NotificationTargetType targetType,
            UUID targetId) {
        notifyReceived(actorId, recipientId, targetType, targetId, NotificationType.LIKE_RECEIVED);
    }

    @Override
    @Transactional
    public void notifyCommentReceived(
            UUID actorId,
            UUID recipientId,
            NotificationTargetType targetType,
            UUID targetId) {
        notifyReceived(actorId, recipientId, targetType, targetId, NotificationType.COMMENT_RECEIVED);
    }

    private void notifyReceived(
            UUID actorId,
            UUID recipientId,
            NotificationTargetType targetType,
            UUID targetId,
            NotificationType notificationType) {
        if (Objects.equals(actorId, recipientId)) {
            return;
        }

        User actor = userRepository.getReferenceById(actorId);
        for (int attempt = 0; attempt < MAX_AGGREGATE_ATTEMPTS; attempt++) {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
            UUID insertedId = UUID.randomUUID();
            notificationRepository.insertSocialAggregateIfAbsent(
                    insertedId,
                    recipientId,
                    notificationType.name(),
                    messageFor(actor.getUsername(), 1, notificationType, targetType),
                    actorId,
                    targetType.name(),
                    targetId,
                    now);

            Optional<Notification> aggregateOptional = notificationRepository.findSocialAggregateForUpdate(
                    recipientId, notificationType, targetType, targetId);
            if (aggregateOptional.isEmpty()) {
                continue;
            }

            Notification aggregate = aggregateOptional.get();
            if (Objects.equals(insertedId, aggregate.getId())) {
                return;
            }

            if (Boolean.FALSE.equals(aggregate.getIsRead())) {
                int interactionCount = aggregate.getInteractionCount() + 1;
                aggregate.setInteractionCount(interactionCount);
                aggregate.setLatestActor(actor);
                aggregate.setMessage(messageFor(actor.getUsername(), interactionCount, notificationType, targetType));
                aggregate.setCreatedAt(now);
                aggregate.setUpdatedAt(now);
                aggregate.setIsRead(false);
                notificationRepository.save(aggregate);
                return;
            }

            notificationRepository.delete(aggregate);
            notificationRepository.flush();
            Notification replacement = Notification.builder()
                    .id(UUID.randomUUID())
                    .user(aggregate.getUser())
                    .type(notificationType)
                    .message(messageFor(actor.getUsername(), 1, notificationType, targetType))
                    .latestActor(actor)
                    .targetType(targetType)
                    .targetId(targetId)
                    .interactionCount(1)
                    .isRead(false)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            notificationRepository.saveAndFlush(replacement);
            return;
        }

        throw new IllegalStateException("Social notification aggregate was not created");
    }

    private String messageFor(
            String username,
            int interactionCount,
            NotificationType notificationType,
            NotificationTargetType targetType) {
        String verb = notificationType == NotificationType.LIKE_RECEIVED ? "liked" : "commented on";
        String target = targetType.getDisplayLabel();
        if (interactionCount == 1) {
            return username + " " + verb + " your " + target;
        }
        int additionalUsers = interactionCount - 1;
        String userLabel = additionalUsers == 1 ? "user" : "users";
        return username + " and " + additionalUsers + " more " + userLabel + " " + verb + " your " + target;
    }
}
