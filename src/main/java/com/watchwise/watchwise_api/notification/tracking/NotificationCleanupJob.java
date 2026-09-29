package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.service.NotificationCleanupService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationCleanupJob {

    private final NotificationCleanupService notificationCleanupService;

    @Scheduled(cron = "${app.notification.cleanup.cron}")
    public void run() {
        notificationCleanupService.cleanupExpiredSocialNotifications();
    }
}
