package com.watchwise.watchwise_api.notification.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.notification.entity.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponseDTO(
        UUID id,
        NotificationType type,
        String message,
        ContentRefDTO content,
        String personTmdbId,
        boolean isRead,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
