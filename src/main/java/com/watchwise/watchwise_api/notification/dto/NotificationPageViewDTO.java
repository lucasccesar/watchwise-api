package com.watchwise.watchwise_api.notification.dto;

import java.util.List;

public record NotificationPageViewDTO(
        List<NotificationViewDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        long totalNotifications,
        long unreadNotifications
) {
}
