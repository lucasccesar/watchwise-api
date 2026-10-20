package com.watchwise.watchwise_api.report.service;

import com.watchwise.watchwise_api.report.dto.AdminReportCaseDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCaseDetailDTO;
import com.watchwise.watchwise_api.report.dto.AdminReportCasePatchDTO;
import com.watchwise.watchwise_api.report.entity.ReportCaseStatus;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface AdminReportService {
    Page<AdminReportCaseDTO> list(UUID adminId, ReportCaseStatus status, Integer page, Integer size);
    AdminReportCaseDetailDTO get(UUID adminId, UUID caseId);
    AdminReportCaseDTO update(UUID adminId, UUID caseId, AdminReportCasePatchDTO request);
}
