package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.notification.service.NotificationCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class NotificationCleanupServiceImpl implements NotificationCleanupService {

    private static final int BATCH_SIZE = 500;
    private static final int READ_RETENTION_DAYS = 7;
    private static final int UNREAD_RETENTION_DAYS = 30;

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @Override
    public int cleanupExpiredSocialNotifications() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        int deleted = deleteUntilExhausted(true, now.minusDays(READ_RETENTION_DAYS));
        deleted += deleteUntilExhausted(false, now.minusDays(UNREAD_RETENTION_DAYS));
        return deleted;
    }

    private int deleteUntilExhausted(boolean isRead, LocalDateTime cutoff) {
        int totalDeleted = 0;
        int deletedInBatch;
        do {
            deletedInBatch = notificationRepository.deleteExpiredSocialNotifications(isRead, cutoff, BATCH_SIZE);
            totalDeleted += deletedInBatch;
        } while (deletedInBatch == BATCH_SIZE);
        return totalDeleted;
    }
}
