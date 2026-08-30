package com.watchwise.watchwise_api.notification.service;

import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface NotificationService {

    Page<NotificationResponseDTO> getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize);

    void markAsRead(UUID userId, UUID notificationId);

}
