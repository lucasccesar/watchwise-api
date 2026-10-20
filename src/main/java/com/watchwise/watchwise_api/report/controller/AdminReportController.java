package com.watchwise.watchwise_api.report.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDetailDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCasePatchDTO;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import com.watchwise.watchwise_api.report.service.AdminReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService adminReportService;

    @GetMapping("/admin/reports")
    public ResponseEntity<PageResponseDTO<AdminReportCaseDTO>> list(
            @RequestParam ReportCaseStatus status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<AdminReportCaseDTO> result = adminReportService.list(currentUserId(), status, page, size);
        return ResponseEntity.ok(PageResponseDTO.of(result));
    }

    @GetMapping("/admin/reports/{caseId}")
    public ResponseEntity<AdminReportCaseDetailDTO> get(@PathVariable UUID caseId) {
        return ResponseEntity.ok(adminReportService.get(currentUserId(), caseId));
    }

    @PatchMapping("/admin/reports/{caseId}")
    public ResponseEntity<AdminReportCaseDTO> update(
            @PathVariable UUID caseId,
            @Valid @RequestBody AdminReportCasePatchDTO request) {
        return ResponseEntity.ok(adminReportService.update(currentUserId(), caseId, request));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
