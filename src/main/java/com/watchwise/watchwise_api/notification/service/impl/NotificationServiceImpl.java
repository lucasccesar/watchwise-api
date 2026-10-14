package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.notification.dto.NotificationPageViewDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationResponseDTO;
import com.watchwise.watchwise_api.notification.dto.NotificationViewDTO;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.mapper.NotificationMapper;
import com.watchwise.watchwise_api.notification.repository.NotificationCountsProjection;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final PageRequestFactory pageRequestFactory;
    private final NotificationViewAssembler notificationViewAssembler;

    @Override
    public Page<NotificationResponseDTO> getNotifications(UUID userId, Boolean isRead, Integer pageNumber, Integer pageSize) {
        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);

        Page<Notification> page = isRead == null
                ? notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageRequest)
                : notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(userId, isRead, pageRequest);

        return page.map(notificationMapper::notificationToNotificationResponseDto);
    }

    @Override
    public NotificationPageViewDTO getNotificationsView(
            UUID authenticatedUserId, Boolean isRead, Integer page, Integer size) {
        PageRequest pageRequest = pageRequestFactory.build(page, size, 20);

        Page<Notification> notifications = isRead == null
                ? notificationRepository.findByUserIdOrderByCreatedAtDesc(authenticatedUserId, pageRequest)
                : notificationRepository.findByUserIdAndIsReadOrderByCreatedAtDesc(
                        authenticatedUserId, isRead, pageRequest);
        Page<NotificationResponseDTO> rawPage = notifications.map(notificationMapper::notificationToNotificationResponseDto);
        List<NotificationViewDTO> content = notificationViewAssembler.assemble(rawPage.getContent(), authenticatedUserId);
        NotificationCountsProjection counts = notificationRepository.findCountsByUserId(authenticatedUserId);

        return new NotificationPageViewDTO(
                content,
                rawPage.getNumber() + 1,
                rawPage.getSize(),
                rawPage.getTotalElements(),
                rawPage.getTotalPages(),
                rawPage.hasNext(),
                counts.getTotalNotifications(),
                counts.getUnreadNotifications());
    }

    @Override
    @Transactional
    public void markAsRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found"));

        if (!notification.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This notification does not belong to you");
        }

        notification.setIsRead(true);
        notification.setUpdatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }
}
