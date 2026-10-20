package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportNotificationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-20T14:00:00Z");

    @Mock NotificationRepository notificationRepository;
    @Mock ReportSubmissionRepository reportSubmissionRepository;
    ReportNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReportNotificationServiceImpl(notificationRepository, reportSubmissionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void notifiesEachReporterWhenCaseIsResolvedWithoutExposingNotes() {
        UUID caseId = UUID.randomUUID();
        User reporterA = user(UUID.randomUUID(), "reporter-a");
        User reporterB = user(UUID.randomUUID(), "reporter-b");
        when(reportSubmissionRepository.findWithReporterByReportCaseId(caseId)).thenReturn(List.of(
                ReportSubmission.builder().reporter(reporterA).createdAt(LocalDateTime.now()).build(),
                ReportSubmission.builder().reporter(reporterB).createdAt(LocalDateTime.now()).build()));

        service.notifyCaseClosed(caseId, ReportCaseStatus.RESOLVED);

        ArgumentCaptor<List<Notification>> notifications = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(notifications.capture());
        assertThat(notifications.getValue()).hasSize(2).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.REPORT_RESOLVED);
            assertThat(notification.getMessage()).isEqualTo("Your report was resolved");
            assertThat(notification.getTargetType()).isEqualTo(NotificationTargetType.REPORT_CASE);
            assertThat(notification.getTargetId()).isEqualTo(caseId);
            assertThat(notification.getLatestActor()).isNull();
            assertThat(notification.getContent()).isNull();
            assertThat(notification.getInteractionCount()).isEqualTo(1);
            assertThat(notification.getIsRead()).isFalse();
        });
        assertThat(notifications.getValue()).extracting(Notification::getUser)
                .containsExactlyInAnyOrder(reporterA, reporterB);
    }

    @Test
    void distinguishesDismissalAndDoesNotNotifyForNonterminalStatus() {
        UUID caseId = UUID.randomUUID();
        User reporter = user(UUID.randomUUID(), "reporter");
        when(reportSubmissionRepository.findWithReporterByReportCaseId(caseId))
                .thenReturn(List.of(ReportSubmission.builder().reporter(reporter).createdAt(LocalDateTime.now()).build()));

        service.notifyCaseClosed(caseId, ReportCaseStatus.DISMISSED);
        service.notifyCaseClosed(caseId, ReportCaseStatus.IN_REVIEW);

        ArgumentCaptor<List<Notification>> notifications = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(notifications.capture());
        assertThat(notifications.getValue()).singleElement()
                .extracting(Notification::getMessage).isEqualTo("Your report was dismissed");
        verify(reportSubmissionRepository, times(1)).findWithReporterByReportCaseId(caseId);
    }

    private static User user(UUID id, String username) {
        return User.builder().id(id).username(username).name(username).email(username + "@example.com")
                .password("hash").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
