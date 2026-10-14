package com.watchwise.watchwise_api.notification.service;

import com.watchwise.watchwise_api.notification.dto.NotificationPageViewDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface NotificationService {

    Page<NotificationResponseDTO> getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize);

    NotificationPageViewDTO getNotificationsView(UUID authenticatedUserId, Boolean isRead, Integer page, Integer size);

    void markAsRead(UUID userId, UUID notificationId);

}
