package com.watchwise.watchwise_api.contentreview.controller;

import com.watchwise.watchwise_api.contentreview.dto.ContentReviewCreationDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewPatchDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewViewDTO;
import com.watchwise.watchwise_api.contentreview.service.ContentReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ContentReviewController {

    private final ContentReviewService contentReviewService;

    @PostMapping("/contents/{contentId}/reviews")
    public ResponseEntity<ContentReviewViewDTO> create(
            @PathVariable UUID contentId,
            @Valid @RequestBody ContentReviewCreationDTO request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contentReviewService.create(
                currentUserId(), contentId, request, idempotencyKey));
    }

    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<ContentReviewViewDTO> get(@PathVariable UUID reviewId) {
        return ResponseEntity.ok(contentReviewService.get(currentUserId(), reviewId));
    }

    @PatchMapping("/reviews/{reviewId}")
    public ResponseEntity<ContentReviewViewDTO> edit(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ContentReviewPatchDTO request) {
        return ResponseEntity.ok(contentReviewService.edit(currentUserId(), reviewId, request));
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> delete(@PathVariable UUID reviewId) {
        contentReviewService.delete(currentUserId(), reviewId);
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
