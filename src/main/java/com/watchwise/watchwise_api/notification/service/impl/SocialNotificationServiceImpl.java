package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.SocialNotificationService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
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
    private final UserBlockAccessService userBlockAccessService;

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
        if (Objects.equals(actorId, recipientId)
                || userBlockAccessService.isBlockedEitherDirection(actorId, recipientId)) {
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
                notificationRepository.insertSocialAggregateActorIfAbsent(aggregate.getId(), actorId);
                return;
            }

            if (Boolean.FALSE.equals(aggregate.getIsRead())) {
                boolean hasActorHistory = notificationRepository.hasSocialAggregateActors(aggregate.getId());
                if (!hasActorHistory && aggregate.getInteractionCount() > 1) {
                    notificationRepository.delete(aggregate);
                    notificationRepository.flush();
                    Notification replacement = buildAggregate(
                            aggregate.getUser(), actor, notificationType, targetType, targetId, now);
                    Notification saved = notificationRepository.saveAndFlush(replacement);
                    notificationRepository.insertSocialAggregateActorIfAbsent(saved.getId(), actorId);
                    return;
                }
                if (!hasActorHistory && aggregate.getLatestActor() != null) {
                    notificationRepository.insertSocialAggregateActorIfAbsent(
                            aggregate.getId(), aggregate.getLatestActor().getId());
                }
                notificationRepository.insertSocialAggregateActorIfAbsent(aggregate.getId(), actorId);
                int actorCount = Math.toIntExact(notificationRepository.countSocialAggregateActors(aggregate.getId()));
                int interactionCount = aggregate.getInteractionCount() + 1;
                aggregate.setInteractionCount(interactionCount);
                aggregate.setLatestActor(actor);
                aggregate.setMessage(messageFor(actor.getUsername(), actorCount, notificationType, targetType));
                aggregate.setCreatedAt(now);
                aggregate.setUpdatedAt(now);
                aggregate.setIsRead(false);
                notificationRepository.save(aggregate);
                return;
            }

            notificationRepository.delete(aggregate);
            notificationRepository.flush();
            Notification replacement = buildAggregate(
                    aggregate.getUser(), actor, notificationType, targetType, targetId, now);
            Notification saved = notificationRepository.saveAndFlush(replacement);
            notificationRepository.insertSocialAggregateActorIfAbsent(saved.getId(), actorId);
            return;
        }

        throw new IllegalStateException("Social notification aggregate was not created");
    }

    private Notification buildAggregate(
            User recipient,
            User actor,
            NotificationType notificationType,
            NotificationTargetType targetType,
            UUID targetId,
            LocalDateTime now) {
        return Notification.builder()
                .user(recipient)
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
    }

    private String messageFor(
            String username,
            int actorCount,
            NotificationType notificationType,
            NotificationTargetType targetType) {
        String verb = notificationType == NotificationType.LIKE_RECEIVED ? "liked" : "commented on";
        String target = targetType.getDisplayLabel();
        if (actorCount == 1) {
            return username + " " + verb + " your " + target;
        }
        int additionalUsers = actorCount - 1;
        String userLabel = additionalUsers == 1 ? "user" : "users";
        return username + " and " + additionalUsers + " more " + userLabel + " " + verb + " your " + target;
    }
}
