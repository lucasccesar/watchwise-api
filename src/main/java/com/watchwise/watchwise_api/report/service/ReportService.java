package com.watchwise.watchwise_api.report.service;

import com.watchwise.watchwise_api.report.dto.ReportCreationDTO;
import com.watchwise.watchwise_api.report.dto.ReportSubmissionResponseDTO;

import java.util.UUID;

public interface ReportService {
    ReportSubmissionResponseDTO create(UUID reporterId, ReportCreationDTO request);
}
