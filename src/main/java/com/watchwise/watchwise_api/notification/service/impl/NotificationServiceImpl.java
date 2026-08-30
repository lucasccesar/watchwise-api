package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public Page<NotificationResponseDTO> getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize) {
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<Notification> page = isRead == null
                ? notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest)
                : notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(userId, isRead, pageRequest);

        return page.map(notificationMapper::notificationToNotificationResponseDto);
    }

    @Override
    public void markAsRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));

        if (!notification.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This notification does not belong to you");
        }

        notification.setIsRead(true);
        notification.setUpdatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }
}
