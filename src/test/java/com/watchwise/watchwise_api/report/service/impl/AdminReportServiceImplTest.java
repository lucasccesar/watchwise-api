package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.report.dto.AdminReportCasePatchDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDTO;
import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.report.service.ReportNotificationService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReportServiceImplTest {

    @Mock ReportCaseRepository reportCaseRepository;
    @Mock ReportSubmissionRepository reportSubmissionRepository;
    @Mock UserRepository userRepository;
    @Mock PageRequestFactory pageRequestFactory;
    @Mock ReportNotificationService reportNotificationService;
    @InjectMocks AdminReportServiceImpl adminReportService;

    @Test
    void rejectsARegularUserBeforeReadingTheQueue() {
        UUID actorId = UUID.randomUUID();
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, UserRole.USER)));

        assertThatThrownBy(() -> adminReportService.list(actorId, ReportCaseStatus.OPEN, 1, 20))
                .isInstanceOf(ForbiddenException.class);

        verify(reportCaseRepository, never()).findByStatusOrderByCreatedAtAscIdAsc(any(), any());
    }

    @Test
    void permitsAnAdministratorToMoveAnOpenCaseToReview() {
        UUID adminId = UUID.randomUUID();
        ReportCase reportCase = ReportCase.builder().id(UUID.randomUUID())
                .targetType(ReportTargetType.USER).targetId(UUID.randomUUID())
                .status(ReportCaseStatus.OPEN).createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(user(adminId, UserRole.ADMIN)));
        when(reportCaseRepository.findByIdForUpdate(reportCase.getId())).thenReturn(Optional.of(reportCase));
        when(reportCaseRepository.saveAndFlush(reportCase)).thenReturn(reportCase);

        var result = adminReportService.update(adminId, reportCase.getId(),
                new AdminReportCasePatchDTO(ReportCaseStatus.IN_REVIEW, null));

        assertThat(reportCase.getStatus()).isEqualTo(ReportCaseStatus.IN_REVIEW);
        assertThat(result.status()).isEqualTo(ReportCaseStatus.IN_REVIEW);
        assertThat(reportCase.getClosedAt()).isNull();
    }

    @Test
    void rejectsSkippingTheInReviewState() {
        UUID adminId = UUID.randomUUID();
        ReportCase reportCase = ReportCase.builder().id(UUID.randomUUID())
                .targetType(ReportTargetType.USER).targetId(UUID.randomUUID())
                .status(ReportCaseStatus.OPEN).createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(user(adminId, UserRole.ADMIN)));
        when(reportCaseRepository.findByIdForUpdate(reportCase.getId())).thenReturn(Optional.of(reportCase));

        assertThatThrownBy(() -> adminReportService.update(adminId, reportCase.getId(),
                new AdminReportCasePatchDTO(ReportCaseStatus.RESOLVED, "skip review")))
                .isInstanceOf(BadRequestException.class);
        verify(reportCaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void notifiesAllCurrentReportersWhenAnInReviewCaseIsResolved() {
        UUID adminId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        ReportCase reportCase = ReportCase.builder().id(caseId).targetType(ReportTargetType.USER)
                .targetId(UUID.randomUUID()).status(ReportCaseStatus.IN_REVIEW)
                .createdAt(LocalDateTime.now().minusDays(1)).updatedAt(LocalDateTime.now().minusMinutes(1)).build();
        when(userRepository.findById(adminId)).thenReturn(Optional.of(user(adminId, UserRole.ADMIN)));
        when(reportCaseRepository.findByIdForUpdate(caseId)).thenReturn(Optional.of(reportCase));
        when(reportCaseRepository.saveAndFlush(reportCase)).thenReturn(reportCase);

        AdminReportCaseDTO result = adminReportService.update(adminId, caseId,
                new AdminReportCasePatchDTO(ReportCaseStatus.RESOLVED, "reviewed"));

        assertThat(result.status()).isEqualTo(ReportCaseStatus.RESOLVED);
        assertThat(reportCase.getClosedAt()).isNotNull();
        verify(reportNotificationService).notifyCaseClosed(caseId, ReportCaseStatus.RESOLVED);
    }

    private static User user(UUID id, UserRole role) {
        return User.builder().id(id).role(role).username("admin").name("Admin")
                .email("admin@example.com").password("hash").createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build();
    }
}
