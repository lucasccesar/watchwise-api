package com.watchwise.watchwise_api.notification.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationViewDTO(
        UUID id,
        NotificationType type,
        String message,
        ContentRefDTO content,
        String personTmdbId,
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        UserPreviewDTO latestActor,
        NotificationTargetType targetType,
        UUID targetId,
        Integer interactionCount,
        ContentCardDTO contentCard,
        PersonPreviewDTO person,
        NotificationTargetPreviewDTO targetPreview
) {
}
