package com.watchwise.watchwise_api.contentrating.controller;

import com.watchwise.watchwise_api.contentrating.dto.ContentRatingResponseDTO;
import com.watchwise.watchwise_api.contentrating.dto.ContentRatingUpsertDTO;
import com.watchwise.watchwise_api.contentrating.service.ContentRatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentRatingController {

    private final ContentRatingService contentRatingService;

    @PutMapping("/{contentId}/my-rating")
    public ResponseEntity<ContentRatingResponseDTO> set(
            @PathVariable UUID contentId,
            @Valid @RequestBody ContentRatingUpsertDTO request) {
        return ResponseEntity.ok(contentRatingService.set(
                currentUserId(), contentId, request.score(), request.expectedVersion()));
    }

    @DeleteMapping("/{contentId}/my-rating")
    public ResponseEntity<Void> remove(@PathVariable UUID contentId) {
        contentRatingService.remove(currentUserId(), contentId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
