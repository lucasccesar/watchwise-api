package com.watchwise.watchwise_api.report.repository;

import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ReportRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired ReportCaseRepository reportCaseRepository;
    @Autowired ReportSubmissionRepository reportSubmissionRepository;
    @Autowired UserRepository userRepository;
    @PersistenceContext EntityManager entityManager;

    @Test
    void permitsOnlyOneActiveCaseForATypedTarget() {
        UUID targetId = UUID.randomUUID();
        saveCase(ReportCaseStatus.OPEN, ReportTargetType.USER_LIST, targetId);

        assertThatThrownBy(() -> saveCase(ReportCaseStatus.IN_REVIEW, ReportTargetType.USER_LIST, targetId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permitsAReporterOnlyOncePerCase() {
        UUID targetId = UUID.randomUUID();
        UUID reporterId = saveUser("reporter").getId();
        ReportCase reportCase = saveCase(ReportCaseStatus.OPEN, ReportTargetType.COMMENT, targetId);
        saveSubmission(reportCase, reporterId);

        assertThatThrownBy(() -> saveSubmission(reportCase, reporterId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsANewCaseForTheSameTargetAfterThePreviousCaseCloses() {
        UUID targetId = UUID.randomUUID();
        saveCase(ReportCaseStatus.RESOLVED, ReportTargetType.REVIEW, targetId);

        ReportCase reopened = saveCase(ReportCaseStatus.OPEN, ReportTargetType.REVIEW, targetId);

        assertThat(reopened.getStatus()).isEqualTo(ReportCaseStatus.OPEN);
    }

    @Test
    void scopesActiveCaseUniquenessByTargetType() {
        UUID targetId = UUID.randomUUID();
        saveCase(ReportCaseStatus.OPEN, ReportTargetType.COMMENT, targetId);

        ReportCase otherType = saveCase(ReportCaseStatus.OPEN, ReportTargetType.REVIEW, targetId);

        assertThat(otherType.getTargetType()).isEqualTo(ReportTargetType.REVIEW);
    }

    @Test
    void deletesOnlyClosedCasesOlderThanTheCutoff() {
        LocalDateTime now = LocalDateTime.now();
        ReportCase expired = saveClosedCase(ReportCaseStatus.RESOLVED, now.minusDays(91));
        ReportCase recent = saveClosedCase(ReportCaseStatus.DISMISSED, now.minusDays(89));
        ReportCase active = saveCase(ReportCaseStatus.IN_REVIEW, ReportTargetType.USER, UUID.randomUUID());

        int deleted = reportCaseRepository.deleteClosedBefore(now.minusDays(90), 500);
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(reportCaseRepository.findById(expired.getId())).isEmpty();
        assertThat(reportCaseRepository.findById(recent.getId())).isPresent();
        assertThat(reportCaseRepository.findById(active.getId())).isPresent();
    }

    @Test
    void deletingReporterSubmissionsLeavesSharedCasesAndRemovesEmptyCases() {
        UUID targetId = UUID.randomUUID();
        User reporterA = saveUser("reporter-a");
        User reporterB = saveUser("reporter-b");
        ReportCase shared = saveCase(ReportCaseStatus.OPEN, ReportTargetType.USER_LIST, targetId);
        ReportCase onlyReporterA = saveCase(ReportCaseStatus.OPEN, ReportTargetType.COMMENT, UUID.randomUUID());
        saveSubmission(shared, reporterA.getId());
        saveSubmission(shared, reporterB.getId());
        saveSubmission(onlyReporterA, reporterA.getId());

        reportSubmissionRepository.deleteByReporterId(reporterA.getId());
        reportCaseRepository.deleteEmptyCases();
        entityManager.clear();

        assertThat(reportCaseRepository.findById(shared.getId())).isPresent();
        assertThat(reportSubmissionRepository.findByReportCaseIdOrderByCreatedAtAsc(shared.getId()))
                .extracting(submission -> submission.getReporter().getId()).containsExactly(reporterB.getId());
        assertThat(reportCaseRepository.findById(onlyReporterA.getId())).isEmpty();
    }

    @Test
    void findsDistinctActiveTargetsForReporterInStableOrder() {
        User reporter = saveUser("active-target-reporter");
        UUID commentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ReportCase activeComment = saveCase(ReportCaseStatus.OPEN, ReportTargetType.COMMENT, commentId);
        ReportCase closedComment = saveCase(ReportCaseStatus.RESOLVED, ReportTargetType.COMMENT, commentId);
        ReportCase activeUser = saveCase(ReportCaseStatus.IN_REVIEW, ReportTargetType.USER, userId);
        saveSubmission(activeComment, reporter.getId());
        saveSubmission(closedComment, reporter.getId());
        saveSubmission(activeUser, reporter.getId());

        var targets = reportSubmissionRepository.findActiveTargetIdentitiesByReporterId(
                reporter.getId(), List.of(ReportCaseStatus.OPEN, ReportCaseStatus.IN_REVIEW));

        assertThat(targets)
                .extracting(target -> target.getTargetType().name() + "|" + target.getTargetId())
                .containsExactly("COMMENT|" + commentId, "USER|" + userId);
    }

    @Test
    void indexesReporterSubmissionsForAccountDeletionCleanup() {
        @SuppressWarnings("unchecked")
        List<String> indexes = entityManager.createNativeQuery(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'report_submissions'")
                .getResultList();

        assertThat(indexes).contains("idx_report_submissions_reporter_id");
    }

    private ReportCase saveCase(ReportCaseStatus status, ReportTargetType targetType, UUID targetId) {
        LocalDateTime now = LocalDateTime.now();
        return reportCaseRepository.saveAndFlush(ReportCase.builder()
                .targetType(targetType).targetId(targetId).status(status)
                .createdAt(now).updatedAt(now)
                .closedAt(status == ReportCaseStatus.RESOLVED || status == ReportCaseStatus.DISMISSED ? now : null)
                .build());
    }

    private ReportCase saveClosedCase(ReportCaseStatus status, LocalDateTime closedAt) {
        return reportCaseRepository.saveAndFlush(ReportCase.builder()
                .targetType(ReportTargetType.USER).targetId(UUID.randomUUID()).status(status)
                .createdAt(closedAt).updatedAt(closedAt).closedAt(closedAt).build());
    }

    private ReportSubmission saveSubmission(ReportCase reportCase, UUID reporterId) {
        return reportSubmissionRepository.saveAndFlush(ReportSubmission.builder()
                .reportCase(reportCase).reporter(userRepository.findById(reporterId).orElseThrow())
                .reason(ReportReason.SPAM_OR_MISLEADING).targetSnapshot("{\"type\":\"COMMENT\"}")
                .createdAt(LocalDateTime.now()).build());
    }

    private User saveUser(String username) {
        LocalDateTime now = LocalDateTime.now();
        return userRepository.saveAndFlush(User.builder().username(username).name(username)
                .email(username + "@example.com").password("hash").profilePicture("https://example.com/p.png")
                .isProfilePublic(true).createdAt(now).updatedAt(now).build());
    }
}
