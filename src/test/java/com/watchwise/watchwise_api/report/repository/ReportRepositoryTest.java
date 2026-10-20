package com.watchwise.watchwise_api.report.repository;

import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportSubmission;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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

    private ReportCase saveCase(ReportCaseStatus status, ReportTargetType targetType, UUID targetId) {
        LocalDateTime now = LocalDateTime.now();
        return reportCaseRepository.saveAndFlush(ReportCase.builder()
                .targetType(targetType).targetId(targetId).status(status)
                .createdAt(now).updatedAt(now).build());
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
