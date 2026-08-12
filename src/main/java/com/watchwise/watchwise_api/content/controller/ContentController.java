package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.service.ContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.content-reference.max-requests}")
    private int contentReferenceMaxRequests;
    @Value("${app.rate-limit.content-reference.window-minutes}")
    private long contentReferenceWindowMinutes;

    @PostMapping("/reference")
    public ResponseEntity<ContentRefDTO> getOrCreateReference(@Valid @RequestBody ContentRefCreationDTO contentRefCreationDTO) {
        requestThrottler.checkAllowed(contentReferenceKey(), contentReferenceMaxRequests, Duration.ofMinutes(contentReferenceWindowMinutes));

        ContentRefDTO contentRefDTO = contentService.getOrCreateReference(contentRefCreationDTO);
        return ResponseEntity.ok(contentRefDTO);
    }

    private String contentReferenceKey() {
        return "content-reference|" + getCurrentUserId();
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}