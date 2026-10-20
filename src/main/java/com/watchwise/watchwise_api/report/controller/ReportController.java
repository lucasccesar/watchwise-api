package com.watchwise.watchwise_api.report.controller;

import com.watchwise.watchwise_api.report.dto.ReportCreationDTO;
import com.watchwise.watchwise_api.report.dto.ReportSubmissionResponseDTO;
import com.watchwise.watchwise_api.report.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/reports")
    public ResponseEntity<ReportSubmissionResponseDTO> create(@Valid @RequestBody ReportCreationDTO request) {
        ReportSubmissionResponseDTO response = reportService.create(currentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
