package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.report.service.ReportNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportNotificationServiceImpl implements ReportNotificationService {

    private final NotificationRepository notificationRepository;
    private final ReportSubmissionRepository reportSubmissionRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void notifyCaseClosed(UUID caseId, ReportCaseStatus finalStatus) {
        if (finalStatus != ReportCaseStatus.RESOLVED && finalStatus != ReportCaseStatus.DISMISSED) {
            return;
        }
        var submissions = reportSubmissionRepository.findWithReporterByReportCaseId(caseId);
        if (submissions.isEmpty()) {
            return;
        }

        String message = finalStatus == ReportCaseStatus.RESOLVED
                ? "Your report was resolved"
                : "Your report was dismissed";
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        List<Notification> notifications = submissions.stream()
                .map(submission -> Notification.builder()
                        .user(submission.getReporter())
                        .type(NotificationType.REPORT_RESOLVED)
                        .message(message)
                        .latestActor(null)
                        .targetType(NotificationTargetType.REPORT_CASE)
                        .targetId(caseId)
                        .interactionCount(1)
                        .isRead(false)
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();
        notificationRepository.saveAll(notifications);
    }
}
