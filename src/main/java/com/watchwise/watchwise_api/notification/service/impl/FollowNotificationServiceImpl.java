package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.FollowNotificationService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;

@Service
@RequiredArgsConstructor
public class FollowNotificationServiceImpl implements FollowNotificationService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;
    private final UserBlockAccessService userBlockAccessService;

    @Override
    public void notifyFollowRequested(User requester, User profileOwner) {
        create(profileOwner, requester, NotificationType.FOLLOW_REQUEST,
                requester.getUsername() + " requested to follow you");
    }

    @Override
    public void notifyNewFollower(User follower, User profileOwner) {
        create(profileOwner, follower, NotificationType.NEW_FOLLOWER,
                follower.getUsername() + " started following you");
    }

    @Override
    public void notifyFollowRequestsAccepted(User profileOwner, Collection<User> requesters) {
        if (requesters.isEmpty()) {
            return;
        }
        createAll(profileOwner, requesters);
    }

    private void create(User recipient, User actor, NotificationType type, String message) {
        if (userBlockAccessService.isBlockedEitherDirection(recipient.getId(), actor.getId())) {
            return;
        }
        LocalDateTime now = now();
        notificationRepository.save(Notification.builder()
                .user(recipient)
                .latestActor(actor)
                .type(type)
                .message(message)
                .isRead(false)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private void createAll(User profileOwner, Collection<User> requesters) {
        LocalDateTime now = now();
        notificationRepository.saveAll(requesters.stream()
                .filter(requester -> !userBlockAccessService.isBlockedEitherDirection(
                        profileOwner.getId(), requester.getId()))
                .map(requester -> Notification.builder()
                        .user(requester)
                        .latestActor(profileOwner)
                        .type(NotificationType.FOLLOW_REQUEST_ACCEPTED)
                        .message(profileOwner.getUsername() + " accepted your follow request")
                        .isRead(false)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
