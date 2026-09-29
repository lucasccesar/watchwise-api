package com.watchwise.watchwise_api.notification.service;

import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;

import java.util.UUID;

public interface SocialNotificationService {

    void notifyLikeReceived(
            UUID actorId,
            UUID recipientId,
            NotificationTargetType targetType,
            UUID targetId);

    void notifyCommentReceived(
            UUID actorId,
            UUID recipientId,
            NotificationTargetType targetType,
            UUID targetId);
}
