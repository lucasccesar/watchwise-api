package com.watchwise.watchwise_api.notification.service.impl;

import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationCleanupServiceImplTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-29T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final int BATCH_SIZE = 500;

    @Mock
    private NotificationRepository notificationRepository;

    private NotificationCleanupServiceImpl notificationCleanupService;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        notificationCleanupService = new NotificationCleanupServiceImpl(notificationRepository, CLOCK);
        now = LocalDateTime.ofInstant(CLOCK.instant(), ZoneId.systemDefault());
    }

    @Test
    @DisplayName("[cleanupExpiredSocialNotifications] Should Delete Only Read Rows Strictly Before Seven-Day Cutoff")
    void shouldDeleteOnlyReadRowsStrictlyBeforeSevenDayCutoff() {
        LocalDateTime cutoff = now.minusDays(7);
        when(notificationRepository.deleteExpiredSocialNotifications(true, cutoff, BATCH_SIZE))
                .thenAnswer(invocation -> countStrictlyBeforeCutoff(cutoff, cutoff.minusSeconds(1), cutoff));

        int deleted = notificationCleanupService.cleanupExpiredSocialNotifications();

        assertThat(deleted).isEqualTo(1);
        verify(notificationRepository).deleteExpiredSocialNotifications(true, cutoff, BATCH_SIZE);
        verify(notificationRepository).deleteExpiredSocialNotifications(false, now.minusDays(30), BATCH_SIZE);
    }

    @Test
    @DisplayName("[cleanupExpiredSocialNotifications] Should Delete Only Unread Rows Strictly Before Thirty-Day Cutoff")
    void shouldDeleteOnlyUnreadRowsStrictlyBeforeThirtyDayCutoff() {
        LocalDateTime cutoff = now.minusDays(30);
        when(notificationRepository.deleteExpiredSocialNotifications(true, now.minusDays(7), BATCH_SIZE))
                .thenReturn(0);
        when(notificationRepository.deleteExpiredSocialNotifications(false, cutoff, BATCH_SIZE))
                .thenAnswer(invocation -> countStrictlyBeforeCutoff(cutoff, cutoff.minusSeconds(1), cutoff));

        int deleted = notificationCleanupService.cleanupExpiredSocialNotifications();

        assertThat(deleted).isEqualTo(1);
        verify(notificationRepository).deleteExpiredSocialNotifications(true, now.minusDays(7), BATCH_SIZE);
        verify(notificationRepository).deleteExpiredSocialNotifications(false, cutoff, BATCH_SIZE);
    }

    @Test
    @DisplayName("[cleanupExpiredSocialNotifications] Should Repeat Full Read Batches And Sum Both States")
    void shouldRepeatFullReadBatchesAndSumBothStates() {
        LocalDateTime readCutoff = now.minusDays(7);
        LocalDateTime unreadCutoff = now.minusDays(30);
        when(notificationRepository.deleteExpiredSocialNotifications(true, readCutoff, BATCH_SIZE))
                .thenReturn(BATCH_SIZE, 12);
        when(notificationRepository.deleteExpiredSocialNotifications(false, unreadCutoff, BATCH_SIZE))
                .thenReturn(BATCH_SIZE, 8);

        int deleted = notificationCleanupService.cleanupExpiredSocialNotifications();

        assertThat(deleted).isEqualTo(1_020);
        verify(notificationRepository, org.mockito.Mockito.times(2))
                .deleteExpiredSocialNotifications(true, readCutoff, BATCH_SIZE);
        verify(notificationRepository, org.mockito.Mockito.times(2))
                .deleteExpiredSocialNotifications(false, unreadCutoff, BATCH_SIZE);
    }

    private int countStrictlyBeforeCutoff(LocalDateTime cutoff, LocalDateTime... updatedAtValues) {
        int count = 0;
        for (LocalDateTime updatedAt : updatedAtValues) {
            if (updatedAt.isBefore(cutoff)) {
                count++;
            }
        }
        return count;
    }
}
