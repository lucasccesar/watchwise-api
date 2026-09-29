package com.watchwise.watchwise_api.notification.tracking;

import com.watchwise.watchwise_api.notification.service.NotificationCleanupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupJobTest {

    @Mock
    private NotificationCleanupService notificationCleanupService;

    @InjectMocks
    private NotificationCleanupJob notificationCleanupJob;

    @Test
    @DisplayName("[run] Should Delegate Once To NotificationCleanupService - When Called")
    void shouldDelegateOnceToNotificationCleanupServiceWhenCalled() {
        notificationCleanupJob.run();

        verify(notificationCleanupService).cleanupExpiredSocialNotifications();
        verifyNoMoreInteractions(notificationCleanupService);
    }
}
