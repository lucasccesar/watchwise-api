package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;

import java.util.UUID;

public record NotificationTargetKey(NotificationTargetType targetType, UUID targetId) {
}
