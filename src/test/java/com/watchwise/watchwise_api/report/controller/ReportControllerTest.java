package com.watchwise.watchwise_api.report.controller;

import com.watchwise.watchwise_api.report.dto.ReportCreationDTO;
import com.watchwise.watchwise_api.report.dto.ReportSubmissionResponseDTO;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.entity.ReportReason;
import com.watchwise.watchwise_api.report.entity.ReportTargetType;
import com.watchwise.watchwise_api.report.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class ReportControllerTest {

    @Mock ReportService reportService;
    @InjectMocks ReportController reportController;
    UUID reporterId;

    @BeforeEach
    void setUp() {
        reporterId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(reporterId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsReportForAuthenticatedReporterAndReturnsCreated() {
        UUID targetId = UUID.randomUUID();
        ReportCreationDTO request = new ReportCreationDTO(
                ReportTargetType.COMMENT, targetId, ReportReason.UNMARKED_SPOILER, null);
        ReportSubmissionResponseDTO response = new ReportSubmissionResponseDTO(
                ReportCaseStatus.OPEN, LocalDateTime.now());
        when(reportService.create(reporterId, request)).thenReturn(response);

        ResponseEntity<ReportSubmissionResponseDTO> result = reportController.create(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(response);
        verify(reportService).create(reporterId, request);
    }
}
