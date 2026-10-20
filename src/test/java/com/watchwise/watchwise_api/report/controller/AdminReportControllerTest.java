package com.watchwise.watchwise_api.report.controller;

import com.watchwise.watchwise_api.report.dto.AdminReportCaseDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDetailDTO;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.service.AdminReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReportControllerTest {

    @Mock AdminReportService adminReportService;
    @InjectMocks AdminReportController controller;
    UUID adminId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsCasesWithAuthenticatedAdministrator() {
        AdminReportCaseDTO dto = new AdminReportCaseDTO(UUID.randomUUID(), ReportTargetType.USER,
                UUID.randomUUID(), ReportCaseStatus.OPEN, 2, LocalDateTime.now(), LocalDateTime.now(), null);
        when(adminReportService.list(adminId, ReportCaseStatus.OPEN, 1, 20))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 20), 1));

        ResponseEntity<com.watchwise.watchwise_api.common.dto.PageResponseDTO<AdminReportCaseDTO>> result =
                controller.list(ReportCaseStatus.OPEN, 1, 20);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
        verify(adminReportService).list(adminId, ReportCaseStatus.OPEN, 1, 20);
    }

    @Test
    void readsCaseWithAuthenticatedAdministrator() {
        UUID caseId = UUID.randomUUID();
        AdminReportCaseDetailDTO dto = new AdminReportCaseDetailDTO(caseId, ReportTargetType.USER,
                UUID.randomUUID(), ReportCaseStatus.OPEN, null, LocalDateTime.now(), LocalDateTime.now(), null,
                List.of());
        when(adminReportService.get(adminId, caseId)).thenReturn(dto);

        assertThat(controller.get(caseId).getBody()).isEqualTo(dto);
        verify(adminReportService).get(adminId, caseId);
    }
}
