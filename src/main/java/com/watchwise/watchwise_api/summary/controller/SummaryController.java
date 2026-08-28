package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.service.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryService summaryService;

    @GetMapping("/users/{userId}/summary")
    public ResponseEntity<SummaryResponseDTO> getSummary(
            @PathVariable UUID userId,
            @RequestParam(required = false) ContentType type
    ) {
        SummaryResponseDTO summary = summaryService.getSummary(getCurrentUserId(), userId, type);
        return ResponseEntity.ok(summary);
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
