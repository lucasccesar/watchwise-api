package com.watchwise.watchwise_api.report.service.impl;

import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.common.transaction.ReportTargetLockIdentity;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.report.dto.ReportCreationDTO;
import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.report.service.ResolvedReportTarget;
import com.watchwise.watchwise_api.report.service.ReportTargetResolver;
import com.watchwise.watchwise_api.report.service.impl.ReportServiceImpl;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceImplTest {

    @Mock ReportCaseRepository reportCaseRepository;
    @Mock ReportSubmissionRepository reportSubmissionRepository;
    @Mock ReportTargetResolver reportTargetResolver;
    @Mock UserRepository userRepository;
    @Mock AdvisoryLock advisoryLock;
    @InjectMocks ReportServiceImpl reportService;

    @Test
    void groupsDifferentReportersIntoOneActiveCase() {
        UUID targetId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID reporterA = UUID.randomUUID();
        UUID reporterB = UUID.randomUUID();
        ReportCase active = ReportCase.builder().id(UUID.randomUUID()).targetType(ReportTargetType.USER)
                .targetId(targetId).status(ReportCaseStatus.OPEN).createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build();
        when(reportTargetResolver.resolve(reporterA, ReportTargetType.USER, targetId))
                .thenReturn(new ResolvedReportTarget(targetId, ownerId, "{\"targetType\":\"USER\"}"));
        when(reportTargetResolver.resolve(reporterB, ReportTargetType.USER, targetId))
                .thenReturn(new ResolvedReportTarget(targetId, ownerId, "{\"targetType\":\"USER\"}"));
        when(userRepository.findByIdForLifecycle(reporterA)).thenReturn(Optional.of(user(reporterA)));
        when(userRepository.findByIdForLifecycle(reporterB)).thenReturn(Optional.of(user(reporterB)));
        when(reportCaseRepository.findActiveForUpdate(ReportTargetType.USER, targetId))
                .thenReturn(Optional.empty(), Optional.of(active));
        when(reportCaseRepository.saveAndFlush(any(ReportCase.class)))
                .thenAnswer(invocation -> {
                    ReportCase saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });
        when(reportSubmissionRepository.saveAndFlush(any(ReportSubmission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(reporterA)).thenReturn(user(reporterA));
        when(userRepository.getReferenceById(reporterB)).thenReturn(user(reporterB));

        var first = reportService.create(reporterA,
                new ReportCreationDTO(ReportTargetType.USER, targetId, ReportReason.HATE_SPEECH, null));
        var second = reportService.create(reporterB,
                new ReportCreationDTO(ReportTargetType.USER, targetId, ReportReason.HATE_SPEECH, "details"));

        assertThat(first.status()).isEqualTo(ReportCaseStatus.OPEN);
        assertThat(second.status()).isEqualTo(ReportCaseStatus.OPEN);
        verify(reportCaseRepository, times(1)).saveAndFlush(any(ReportCase.class));
        verify(reportSubmissionRepository, times(2)).saveAndFlush(any(ReportSubmission.class));
        verify(advisoryLock, times(2)).lock(ReportTargetLockIdentity.of(ReportTargetType.USER, targetId));
        InOrder lockOrder = org.mockito.Mockito.inOrder(userRepository, advisoryLock);
        lockOrder.verify(userRepository).findByIdForLifecycle(reporterA);
        lockOrder.verify(advisoryLock).lock(ReportTargetLockIdentity.of(ReportTargetType.USER, targetId));
        lockOrder.verify(userRepository).findByIdForLifecycle(reporterB);
        lockOrder.verify(advisoryLock).lock(ReportTargetLockIdentity.of(ReportTargetType.USER, targetId));
    }

    @Test
    void returnsTheExistingSubmissionWithoutWritingADuplicate() {
        UUID targetId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        LocalDateTime submittedAt = LocalDateTime.now().minusMinutes(5);
        ReportCase active = ReportCase.builder().id(UUID.randomUUID()).targetType(ReportTargetType.COMMENT)
                .targetId(targetId).status(ReportCaseStatus.IN_REVIEW).createdAt(submittedAt)
                .updatedAt(submittedAt).build();
        ReportSubmission existing = ReportSubmission.builder().id(UUID.randomUUID()).reportCase(active)
                .reason(ReportReason.SPAM_OR_MISLEADING).createdAt(submittedAt).build();
        when(reportTargetResolver.resolve(reporterId, ReportTargetType.COMMENT, targetId))
                .thenReturn(new ResolvedReportTarget(targetId, UUID.randomUUID(), "{}"));
        when(userRepository.findByIdForLifecycle(reporterId)).thenReturn(Optional.of(user(reporterId)));
        when(reportCaseRepository.findActiveForUpdate(ReportTargetType.COMMENT, targetId))
                .thenReturn(Optional.of(active));
        when(reportSubmissionRepository.findByReportCaseIdAndReporterId(active.getId(), reporterId))
                .thenReturn(Optional.of(existing));

        var result = reportService.create(reporterId,
                new ReportCreationDTO(ReportTargetType.COMMENT, targetId, ReportReason.OTHER, null));

        assertThat(result.status()).isEqualTo(ReportCaseStatus.IN_REVIEW);
        assertThat(result.submittedAt()).isEqualTo(submittedAt);
        verify(reportSubmissionRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        verify(reportCaseRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnmarkedSpoilerReasonForNonTextualTargetsBeforeResolvingTarget() {
        assertThatThrownBy(() -> reportService.create(UUID.randomUUID(),
                new ReportCreationDTO(ReportTargetType.USER, UUID.randomUUID(),
                        ReportReason.UNMARKED_SPOILER, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unmarked spoiler applies only to comments and reviews");

        verifyNoInteractions(reportTargetResolver, reportCaseRepository, reportSubmissionRepository);
    }

    private static com.watchwise.watchwise_api.user.entity.User user(UUID id) {
        return com.watchwise.watchwise_api.user.entity.User.builder().id(id).username("reporter")
                .name("Reporter").email("reporter@example.com").password("hash")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
