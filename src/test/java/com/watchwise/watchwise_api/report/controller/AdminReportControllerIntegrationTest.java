package com.watchwise.watchwise_api.report.controller;

import com.watchwise.watchwise_api.auth.repository.RefreshTokenRepository;
import com.watchwise.watchwise_api.common.security.CookieUtil;
import com.watchwise.watchwise_api.notification.entity.Notification;
import com.watchwise.watchwise_api.notification.entity.NotificationTargetType;
import com.watchwise.watchwise_api.notification.entity.NotificationType;
import com.watchwise.watchwise_api.notification.repository.NotificationRepository;
import com.watchwise.watchwise_api.report.entity.ReportCase;
import com.watchwise.watchwise_api.report.repository.ReportCaseRepository;
import com.watchwise.watchwise_api.report.repository.ReportSubmissionRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminReportControllerIntegrationTest {

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
    @Autowired NotificationRepository notificationRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void cleanDatabase() {
        notificationRepository.deleteAll();
        reportSubmissionRepository.deleteAll();
        reportCaseRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void onlyPersistedAdministratorsCanReviewAndCloseGroupedCases() throws Exception {
        Registered reporterA = register("admin-report-a");
        Registered reporterB = register("admin-report-b");
        Registered target = register("admin-report-target");
        Registered admin = register("admin-report-admin");
        User adminUser = userRepository.findById(admin.id()).orElseThrow();
        adminUser.setRole(UserRole.ADMIN);
        userRepository.saveAndFlush(adminUser);

        String body = reportBody(target.id());
        mockMvc.perform(reportRequest(reporterA, body)).andExpect(status().isCreated());
        mockMvc.perform(reportRequest(reporterB, body)).andExpect(status().isCreated());
        ReportCase reportCase = reportCaseRepository.findByStatusOrderByCreatedAtAscIdAsc(
                com.watchwise.watchwise_api.report.entity.ReportCaseStatus.OPEN,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent().getFirst();

        mockMvc.perform(get("/admin/reports").param("status", "OPEN").param("page", "1").param("size", "10")
                        .cookie(reporterA.access()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/reports").param("status", "OPEN").param("page", "1").param("size", "10")
                        .cookie(admin.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].submissionsCount").value(2));
        mockMvc.perform(get("/admin/reports/" + reportCase.getId()).cookie(admin.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions.length()").value(2))
                .andExpect(jsonPath("$.submissions[0].reporterUsername").exists())
                .andExpect(jsonPath("$.internalNotes").doesNotExist());
        mockMvc.perform(get("/admin/reports/" + reportCase.getId()).cookie(reporterA.access()))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/admin/reports/" + reportCase.getId()).cookie(admin.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_REVIEW\",\"internalNotes\":\"reviewing\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(adminPatch(admin, reportCase.getId(), "{\"status\":\"IN_REVIEW\",\"internalNotes\":\"reviewing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));
        mockMvc.perform(adminPatch(admin, reportCase.getId(), "{\"status\":\"RESOLVED\",\"internalNotes\":\"private moderator note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        assertThat(reportCaseRepository.findById(reportCase.getId()).orElseThrow().getInternalNotes())
                .isEqualTo("private moderator note");
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(reporterA.id(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .filteredOn(notification -> notification.getType() == NotificationType.REPORT_RESOLVED)
                .hasSize(1)
                .allSatisfy(notification -> {
                    assertThat(notification.getMessage()).isEqualTo("Your report was resolved");
                    assertThat(notification.getLatestActor()).isNull();
                    assertThat(notification.getTargetType()).isEqualTo(NotificationTargetType.REPORT_CASE);
                    assertThat(notification.getTargetId()).isEqualTo(reportCase.getId());
                    assertThat(notification.getMessage()).doesNotContain("private moderator note");
                });
    }

    private record Registered(UUID id, Cookie access, Cookie csrf) { }

    private Registered register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"username":"%s","name":"%s","email":"%s@email.com","password":"Password123","isProfilePublic":true}
                """.formatted(username, username, username))).andExpect(status().isCreated()).andReturn();
        User user = userRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(username, username).orElseThrow();
        return new Registered(user.getId(), result.getResponse().getCookie(CookieUtil.ACCESS_TOKEN_COOKIE),
                result.getResponse().getCookie(CookieUtil.CSRF_TOKEN_COOKIE));
    }

    private MockHttpServletRequestBuilder reportRequest(Registered reporter, String body) {
        return post("/reports").cookie(reporter.access(), reporter.csrf())
                .header("X-XSRF-TOKEN", reporter.csrf().getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder adminPatch(Registered admin, UUID caseId, String body) {
        return patch("/admin/reports/" + caseId).cookie(admin.access(), admin.csrf())
                .header("X-XSRF-TOKEN", admin.csrf().getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String reportBody(UUID targetId) {
        return "{\"targetType\":\"USER\",\"targetId\":\"" + targetId
                + "\",\"reason\":\"HARASSMENT_OR_BULLYING\"}";
    }
}
