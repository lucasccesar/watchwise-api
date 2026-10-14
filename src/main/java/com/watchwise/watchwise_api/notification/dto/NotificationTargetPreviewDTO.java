package com.watchwise.watchwise_api.notification.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;

import java.util.UUID;

public record NotificationTargetPreviewDTO(
        NotificationTargetType targetType,
        UUID targetId,
        String title,
        String excerpt,
        Integer score,
        String coverImageUrl,
        ContentCardDTO contentCard,
        ContentPreviewStatus previewStatus
) {
}
