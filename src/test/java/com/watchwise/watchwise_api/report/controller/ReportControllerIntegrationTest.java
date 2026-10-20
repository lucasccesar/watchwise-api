package com.watchwise.watchwise_api.report.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.common.security.RequestThrottlerTestSupport;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.entity.UserBlock;
import com.watchwise.watchwise_api.userblock.repository.UserBlockRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReportControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.docker.compose.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired ReportCaseRepository reportCaseRepository;
    @Autowired ReportSubmissionRepository reportSubmissionRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired ContentRepository contentRepository;
    @Autowired UserBlockRepository userBlockRepository;
    @Autowired FollowerRepository followerRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RequestThrottler requestThrottler;

    @BeforeEach
    void cleanDatabase() {
        reportSubmissionRepository.deleteAll();
        reportCaseRepository.deleteAll();
        commentRepository.deleteAll();
        userBlockRepository.deleteAll();
        followerRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
        RequestThrottlerTestSupport.reset(requestThrottler);
    }

    @Test
    void groupsReportsAndMakesDuplicateSubmissionIdempotent() throws Exception {
        Registered firstReporter = register("report-group-a", true);
        Registered secondReporter = register("report-group-b", true);
        Registered target = register("report-group-target", true);
        String body = reportJson("USER", target.id(), "HATE_SPEECH", "details");

        mockMvc.perform(reportRequest(firstReporter, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.caseId").doesNotExist())
                .andExpect(jsonPath("$.targetSnapshot").doesNotExist());
        mockMvc.perform(reportRequest(firstReporter, body)).andExpect(status().isCreated());
        mockMvc.perform(reportRequest(secondReporter, body)).andExpect(status().isCreated());

        ReportCase active = findOpenCase(ReportTargetType.USER, target.id());
        assertThat(reportCaseRepository.count()).isEqualTo(1);
        assertThat(reportSubmissionRepository.countByReportCaseId(active.getId())).isEqualTo(2);
    }

    @Test
    void allowsReportingABlockedCommentByItsKnownId() throws Exception {
        Registered reporter = register("report-blocked-reporter", true);
        Registered author = register("report-blocked-author", true);
        User reporterEntity = userRepository.findById(reporter.id()).orElseThrow();
        User authorEntity = userRepository.findById(author.id()).orElseThrow();
        Content content = contentRepository.saveAndFlush(Content.builder().tmdbId("550")
                .type(ContentType.MOVIE).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        Comment comment = commentRepository.saveAndFlush(Comment.builder().user(authorEntity).content(content)
                .text("reported comment").containsSpoiler(true).createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now()).build());
        userBlockRepository.saveAndFlush(UserBlock.builder().blocker(reporterEntity).blocked(authorEntity)
                .createdAt(LocalDateTime.now()).build());

        mockMvc.perform(reportRequest(reporter,
                        reportJson("COMMENT", comment.getId(), "UNMARKED_SPOILER", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        ReportCase reportCase = findOpenCase(ReportTargetType.COMMENT, comment.getId());
        String snapshot = reportSubmissionRepository.findByReportCaseIdOrderByCreatedAtAsc(reportCase.getId())
                .getFirst().getTargetSnapshot();
        assertThat(snapshot).contains("\"targetType\":\"COMMENT\"")
                .contains("reported comment")
                .doesNotContain("password", "email");
    }

    @Test
    void rejectsSelfReportsAndSpoilerReasonForNonTextTargets() throws Exception {
        Registered reporter = register("report-validation-user", true);

        mockMvc.perform(reportRequest(reporter,
                        reportJson("USER", reporter.id(), "OTHER", null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(reportRequest(reporter,
                        reportJson("USER", UUID.randomUUID(), "UNMARKED_SPOILER", null)))
                .andExpect(status().isBadRequest());

        assertThat(reportCaseRepository.count()).isZero();
        assertThat(reportSubmissionRepository.count()).isZero();
    }

    @Test
    void allowsReportingAPrivateUserByIdAndRequiresAuthenticationAndCsrf() throws Exception {
        Registered reporter = register("report-private-reporter", true);
        Registered privateTarget = register("report-private-target", false);

        mockMvc.perform(reportRequest(reporter, reportJson("USER", privateTarget.id(), "OTHER", null)))
                .andExpect(status().isCreated());
        assertThat(reportCaseRepository.count()).isEqualTo(1);
        assertThat(reportSubmissionRepository.count()).isEqualTo(1);
        mockMvc.perform(post("/reports").contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("USER", privateTarget.id(), "OTHER", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reports").cookie(reporter.csrf())
                        .header("X-XSRF-TOKEN", reporter.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("USER", privateTarget.id(), "OTHER", null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/reports").cookie(reporter.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson("USER", privateTarget.id(), "OTHER", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingReporterRemovesTheirSubmissionAndTheNowEmptyCase() throws Exception {
        Registered reporter = register("report-delete-reporter", true);
        Registered target = register("report-delete-target", true);
        mockMvc.perform(reportRequest(reporter, reportJson("USER", target.id(), "OTHER", null)))
                .andExpect(status().isCreated());
        assertThat(reportCaseRepository.count()).isEqualTo(1);
        assertThat(reportSubmissionRepository.count()).isEqualTo(1);

        mockMvc.perform(delete("/users/me").cookie(reporter.access(), reporter.csrf())
                        .header("X-XSRF-TOKEN", reporter.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(reporter.id())).isEmpty();
        assertThat(reportSubmissionRepository.count()).isZero();
        assertThat(reportCaseRepository.count()).isZero();
    }

    private record Registered(UUID id, Cookie access, Cookie csrf) { }

    private Registered register(String username, boolean isPublic) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123","isProfilePublic":%s}
                """.formatted(username, username, username, isPublic))).andExpect(status().isCreated()).andReturn();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new Registered(user.getId(), result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE),
                result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    private MockHttpServletRequestBuilder reportRequest(Registered reporter, String json) {
        return post("/reports").cookie(reporter.access(), reporter.csrf())
                .header("X-XSRF-TOKEN", reporter.csrf().getValue())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String reportJson(String type, UUID targetId, String reason, String details) {
        String detailsJson = details == null ? "" : ",\"details\":\"" + details + "\"";
        return "{\"targetType\":\"" + type + "\",\"targetId\":\"" + targetId
                + "\",\"reason\":\"" + reason + "\"" + detailsJson + "}";
    }

    private ReportCase findOpenCase(ReportTargetType type, UUID targetId) {
        return reportCaseRepository.findByStatusOrderByCreatedAtAscIdAsc(
                        com.watchwise.watchwise_api.report.entity.ReportCaseStatus.OPEN, PageRequest.of(0, 50))
                .getContent().stream()
                .filter(reportCase -> reportCase.getTargetType() == type && reportCase.getTargetId().equals(targetId))
                .findFirst().orElseThrow();
    }
}
